// The sharing side of DualAmp Server: settings, reading the music library (foobar2000's and/or folders),
// the HTTP API the device uses (port 8337) and Wi-Fi discovery (UDP 8338). The window is in Window.cs.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace DualAmp
{
    class Track
    {
        public string id, path, title, artist, albumArtist, album, genre, mime;
        public long size, durationMs;
        public double mtime;
        public int year, track, disc, bitrate;
    }

    class FoobarInfo
    {
        public bool Installed;
        public List<string> Folders = new List<string>();
        public List<bool> Readable = new List<bool>();
        public List<string> Files = new List<string>();
        public bool AnyReadable { get { return Readable.Contains(true); } }
    }

    static class Server
    {
        public const string Version = "1.0";
        // DUALAMP_PORT / DUALAMP_DATA: another port and settings folder, for testing next to a running copy.
        public static readonly int HttpPort = ParsePort(Environment.GetEnvironmentVariable("DUALAMP_PORT"));
        public const int DiscoveryPort = 8338;
        static int ParsePort(string s) { int p; return int.TryParse(s, out p) ? p : 8337; }
        public static readonly string PcName = System.Net.Dns.GetHostName();

        static readonly string[] AudioExt = { ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma", ".ape", ".wv", ".aiff", ".aif", ".alac" };
        static readonly Dictionary<string, string> Mime = new Dictionary<string, string> {
            { ".mp3", "audio/mpeg" }, { ".flac", "audio/flac" }, { ".m4a", "audio/mp4" }, { ".aac", "audio/aac" },
            { ".ogg", "audio/ogg" }, { ".opus", "audio/ogg" }, { ".wav", "audio/wav" }, { ".wma", "audio/x-ms-wma" },
            { ".ape", "audio/ape" }, { ".wv", "audio/wavpack" }, { ".aiff", "audio/aiff" }, { ".aif", "audio/aiff" }, { ".alac", "audio/mp4" },
        };

        // --- Settings (in %APPDATA%\DualAmp Server, so the program can sit anywhere) ---------------

        public static readonly string DataDir = Environment.GetEnvironmentVariable("DUALAMP_DATA") ??
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DualAmp Server");
        static readonly string ConfigFile = Path.Combine(DataDir, "config.json");
        static readonly string CacheFile = Path.Combine(DataDir, "tag-cache.json");
        static readonly string LogFile = Path.Combine(DataDir, "log.txt");

        public static List<string> Folders = new List<string>();
        public static bool UseFoobar, SetupDone;
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

        public static void LoadConfig()
        {
            Directory.CreateDirectory(DataDir);
            try
            {
                var c = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(ConfigFile));
                object v;
                if (c.TryGetValue("folders", out v) && v is System.Collections.ArrayList)
                    foreach (var f in (System.Collections.ArrayList)v) Folders.Add(f.ToString());
                if (c.TryGetValue("useFoobar", out v) && v is bool) UseFoobar = (bool)v;
                if (c.TryGetValue("setupDone", out v) && v is bool) SetupDone = (bool)v;
            }
            catch (Exception) { }
            if (Folders.Count > 0 || UseFoobar) SetupDone = true;
            try { File.WriteAllText(LogFile, ""); } catch (Exception) { }
        }

        public static void SaveConfig()
        {
            var c = new Dictionary<string, object> { { "folders", Folders }, { "useFoobar", UseFoobar }, { "setupDone", SetupDone } };
            try { File.WriteAllText(ConfigFile, Json.Serialize(c)); } catch (Exception e) { Log("Couldn't save settings: " + e.Message); }
        }

        // --- Log (shown in the window, also written to log.txt) -----------------------------------

        public static readonly Queue<string> PendingLog = new Queue<string>();
        public static void Log(string text)
        {
            string line = DateTime.Now.ToString("h:mm:ss tt") + "  " + text;
            lock (PendingLog) PendingLog.Enqueue(line);
            try { File.AppendAllText(LogFile, line + Environment.NewLine); } catch (Exception) { }
        }

        // --- foobar2000 ---------------------------------------------------------------------------

        /// <summary>foobar2000's media library: installed?, its music folders (and whether they open), its files.</summary>
        public static FoobarInfo ReadFoobar()
        {
            var info = new FoobarInfo();
            string baseDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "foobar2000-v2", "library-v2.0");
            info.Installed = Directory.Exists(baseDir);
            if (!info.Installed) return info;
            string tmp = Path.Combine(Path.GetTempPath(), "dualamp-fb-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tmp);
            try
            {
                foreach (var dir in Directory.GetDirectories(baseDir))
                {
                    string db = Path.Combine(dir, "content.sqlite");
                    if (!File.Exists(db)) continue;
                    // A copy, since foobar2000 keeps its database open.
                    string copy = Path.Combine(tmp, Path.GetFileName(dir) + ".sqlite");
                    try
                    {
                        File.Copy(db, copy, true);
                        foreach (var row in Sqlite.Query(copy, "select value from config where key='rootPath'"))
                        {
                            if (row[0] == null) continue;
                            string folder = row[0].StartsWith("file://") ? row[0].Substring(7) : row[0];
                            info.Folders.Add(folder);
                            info.Readable.Add(Directory.Exists(folder));
                        }
                        foreach (var row in Sqlite.Query(copy, "select name from media"))
                        {
                            // Entries look like "0+file://C:\Music\a.mp3" (subsong + URL).
                            string n = row[0] ?? "";
                            int at = n.IndexOf("+file://");
                            if (at > 0) info.Files.Add(n.Substring(at + 8));
                        }
                    }
                    catch (Exception e) { Log("Couldn't read foobar2000's library (" + Path.GetFileName(dir) + "): " + e.Message); }
                }
            }
            finally { try { Directory.Delete(tmp, true); } catch (Exception) { } }
            return info;
        }

        // --- Reading the music --------------------------------------------------------------------

        public static FoobarInfo Foobar = new FoobarInfo();
        static Dictionary<string, Track> byId = new Dictionary<string, Track>();
        static List<Track> tracks = new List<Track>();
        static List<string> roots = new List<string>();
        public static int TrackCount { get { lock (Lock) return tracks.Count; } }
        static readonly object Lock = new object();

        /// <summary>Scan progress for the window: 0 idle, 1 finding files, 2 reading tags.</summary>
        public static volatile int Phase;
        public static volatile int Done, Total;
        public static volatile string Current = "";
        static readonly ManualResetEvent FirstScanDone = new ManualResetEvent(false);
        static readonly object ScanLock = new object();
        static bool scanning, rescanAgain;
        public static bool Scanning { get { lock (ScanLock) return scanning; } }

        /// <summary>Reads the library in the background (a request during a scan runs one more scan after it).</summary>
        public static void Rescan()
        {
            lock (ScanLock)
            {
                if (scanning) { rescanAgain = true; return; }
                scanning = true;
            }
            new Thread(() =>
            {
                while (true)
                {
                    try { DoScan(); } catch (Exception e) { Log("Reading failed: " + e.Message); }
                    lock (ScanLock)
                    {
                        if (!rescanAgain) { scanning = false; Phase = 0; break; }
                        rescanAgain = false;
                    }
                }
                FirstScanDone.Set();
            }) { IsBackground = true, Name = "scan" }.Start();
        }

        static string IdOf(string p)
        {
            using (var sha = SHA1.Create())
            {
                var h = sha.ComputeHash(Encoding.UTF8.GetBytes(p.ToLowerInvariant()));
                var sb = new StringBuilder();
                for (int i = 0; i < 8; i++) sb.Append(h[i].ToString("x2"));
                return sb.ToString();
            }
        }

        static void Walk(string dir, List<string> outList)
        {
            string[] files, dirs;
            try { files = Directory.GetFiles(dir); dirs = Directory.GetDirectories(dir); }
            catch (Exception e) { if (outList.Count == 0) Log("Can't open " + dir + ": " + e.Message); return; }
            foreach (var f in files) if (AudioExt.Contains(Path.GetExtension(f).ToLowerInvariant())) outList.Add(f);
            foreach (var d in dirs) Walk(d, outList);
        }

        static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

        static void DoScan()
        {
            var started = DateTime.Now;
            Phase = 1; Done = 0; Total = 0; Current = "";
            var paths = new List<string>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var newRoots = new List<string>();
            Foobar = ReadFoobar();
            if (UseFoobar)
            {
                Log("foobar2000 library: " + Foobar.Files.Count.ToString("N0") + " files");
                foreach (var p in Foobar.Files) if (seen.Add(p)) paths.Add(p);
                newRoots.AddRange(Foobar.Folders);
            }
            foreach (var folder in Folders.ToArray())
            {
                Current = folder;
                Log("Looking for music in " + folder);
                var list = new List<string>();
                Walk(folder, list);
                Log("  " + list.Count.ToString("N0") + " music files");
                newRoots.Add(folder);
                foreach (var p in list) if (seen.Add(p)) paths.Add(p);
            }

            Dictionary<string, Track> cache = new Dictionary<string, Track>();
            try { foreach (var t in Json.Deserialize<List<Track>>(File.ReadAllText(CacheFile))) cache[t.id] = t; } catch (Exception) { }

            var files = paths.Where(p => AudioExt.Contains(Path.GetExtension(p).ToLowerInvariant())).ToList();
            Phase = 2; Total = files.Count; Done = 0;
            var result = new List<Track>();
            int fresh = 0;
            foreach (var p in files)
            {
                Done++;
                Current = p;
                FileInfo fi;
                try { fi = new FileInfo(p); if (!fi.Exists) continue; } catch (Exception) { continue; }
                string id = IdOf(p);
                double mtime = Math.Floor((fi.LastWriteTimeUtc - Epoch).TotalMilliseconds);
                Track hit;
                if (cache.TryGetValue(id, out hit) && hit.mtime == mtime && hit.size == fi.Length && hit.path == p) { result.Add(hit); continue; }
                // Damaged tags give fewer details; the file and folder names fill the gaps.
                var tag = Tags.Read(p, false);
                string ext = Path.GetExtension(p).ToLowerInvariant();
                string mime;
                result.Add(new Track
                {
                    id = id, path = p, size = fi.Length, mtime = mtime,
                    title = tag.Title.Length > 0 ? tag.Title : Path.GetFileNameWithoutExtension(p),
                    artist = tag.Artist.Length > 0 ? tag.Artist : "Unknown Artist",
                    albumArtist = tag.AlbumArtist,
                    album = tag.Album.Length > 0 ? tag.Album : (Path.GetFileName(Path.GetDirectoryName(p)) ?? "Unknown Album"),
                    genre = tag.Genre, year = tag.Year, track = tag.Track, disc = tag.Disc > 0 ? tag.Disc : 1,
                    durationMs = tag.DurationMs, bitrate = tag.Bitrate,
                    mime = Mime.TryGetValue(ext, out mime) ? mime : "audio/mpeg",
                });
                if (++fresh % 250 == 0) Log("Read tags of " + fresh.ToString("N0") + " new files (" + Done.ToString("N0") + " of " + Total.ToString("N0") + ")");
            }
            lock (Lock)
            {
                tracks = result;
                byId = result.ToDictionary(t => t.id);
                roots = newRoots;
            }
            try { File.WriteAllText(CacheFile, Json.Serialize(result)); } catch (Exception) { }
            Log("Sharing " + result.Count.ToString("N0") + " songs (read in " + (DateTime.Now - started).TotalSeconds.ToString("0.0") + " s)");
        }

        /// <summary>"C:\Music\Personal\A\b.mp3" -> "Personal\A\b.mp3" (relative to its library folder).</summary>
        static string DisplayPath(string p, List<string> rootList)
        {
            string best = null;
            foreach (var r in rootList)
            {
                string root = r.TrimEnd('\\', '/');
                if (p.StartsWith(root + "\\", StringComparison.OrdinalIgnoreCase) && (best == null || root.Length > best.Length)) best = root;
            }
            if (best == null) return p;
            return Path.GetFileName(best) + p.Substring(best.Length);
        }

        // --- HTTP (what the device talks to) ---------------------------------------------------------

        public static void StartHttp()
        {
            var listener = new TcpListener(IPAddress.Any, HttpPort);
            listener.Start();
            new Thread(() =>
            {
                while (true)
                {
                    TcpClient c;
                    try { c = listener.AcceptTcpClient(); } catch (Exception) { return; }
                    ThreadPool.QueueUserWorkItem(_ => Handle(c));
                }
            }) { IsBackground = true, Name = "http" }.Start();
        }

        static void Handle(TcpClient client)
        {
            using (client)
            {
                try
                {
                    client.NoDelay = true;
                    var s = client.GetStream();
                    s.ReadTimeout = 15000;
                    // Request line and headers.
                    var head = new MemoryStream();
                    int matched = 0;
                    while (head.Length < 16384)
                    {
                        int b = s.ReadByte();
                        if (b < 0) return;
                        head.WriteByte((byte)b);
                        matched = (b == "\r\n\r\n"[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
                        if (matched == 4) break;
                    }
                    var lines = Encoding.ASCII.GetString(head.ToArray()).Split(new[] { "\r\n" }, StringSplitOptions.None);
                    var first = lines[0].Split(' ');
                    if (first.Length < 2) return;
                    string method = first[0], url = first[1].Split('?')[0];
                    string range = null;
                    foreach (var l in lines)
                        if (l.StartsWith("Range:", StringComparison.OrdinalIgnoreCase)) range = l.Substring(6).Trim();
                    var parts = url.Trim('/').Split('/');

                    if (url == "/api/info")
                        Send(s, 200, "application/json", Json.Serialize(new Dictionary<string, object> { { "app", "dualamp-server" }, { "name", PcName }, { "tracks", TrackCount } }));
                    else if (url == "/api/library")
                    {
                        // Just started: answer once the music has been read, not with an empty list.
                        if (TrackCount == 0 && Scanning) FirstScanDone.WaitOne(TimeSpan.FromMinutes(5));
                        List<Track> list; List<string> rootList;
                        lock (Lock) { list = tracks; rootList = roots; }
                        var items = list.Select(t => new Dictionary<string, object> {
                            { "id", t.id }, { "title", t.title }, { "artist", t.artist }, { "albumArtist", t.albumArtist },
                            { "album", t.album }, { "genre", t.genre }, { "year", t.year }, { "track", t.track }, { "disc", t.disc },
                            { "durationMs", t.durationMs }, { "bitrate", t.bitrate }, { "mime", t.mime }, { "mtime", t.mtime },
                            { "file", DisplayPath(t.path, rootList) },
                        }).ToList();
                        Send(s, 200, "application/json", Json.Serialize(new Dictionary<string, object> { { "name", PcName }, { "tracks", items } }));
                    }
                    else if (parts.Length == 2 && (parts[0] == "stream" || parts[0] == "art"))
                    {
                        Track t;
                        lock (Lock) byId.TryGetValue(parts[1], out t);
                        if (t == null) Send(s, 404, "text/plain", "not found");
                        else if (parts[0] == "stream") SendFile(s, t, method == "HEAD", range);
                        else SendArt(s, t);
                    }
                    else Send(s, 404, "text/plain", "not found");
                }
                catch (Exception) { }
            }
        }

        static void WriteHead(Stream s, int code, string type, long length, string extra)
        {
            string status = code == 200 ? "OK" : code == 206 ? "Partial Content" : code == 416 ? "Range Not Satisfiable" : "Not Found";
            string h = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length +
                "\r\nConnection: close\r\n" + (extra ?? "") + "\r\n";
            var b = Encoding.ASCII.GetBytes(h);
            s.Write(b, 0, b.Length);
        }

        static void Send(Stream s, int code, string type, string body)
        {
            var b = Encoding.UTF8.GetBytes(body);
            WriteHead(s, code, type + (type.StartsWith("text") || type.EndsWith("json") ? "; charset=utf-8" : ""), b.Length, null);
            s.Write(b, 0, b.Length);
        }

        static void SendFile(Stream s, Track t, bool headOnly, string range)
        {
            using (var f = new FileStream(t.path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                long total = f.Length, start = 0, end = total - 1;
                bool partial = false;
                if (range != null && range.StartsWith("bytes="))
                {
                    var r = range.Substring(6).Split(',')[0].Split('-');
                    long a, z;
                    bool hasA = long.TryParse(r[0], out a), hasZ = r.Length > 1 && long.TryParse(r[1], out z);
                    z = hasZ ? long.Parse(r[1]) : 0;
                    if (hasA) { start = a; if (hasZ) end = Math.Min(z, total - 1); }
                    else if (hasZ) { start = Math.Max(0, total - z); }
                    partial = true;
                    if (start >= total || start > end) { WriteHead(s, 416, t.mime, 0, "Content-Range: bytes */" + total + "\r\n"); return; }
                }
                long len = end - start + 1;
                WriteHead(s, partial ? 206 : 200, t.mime, len,
                    "Accept-Ranges: bytes\r\n" + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n" : ""));
                if (headOnly) return;
                f.Seek(start, SeekOrigin.Begin);
                var buf = new byte[64 * 1024];
                while (len > 0)
                {
                    int n = f.Read(buf, 0, (int)Math.Min(buf.Length, len));
                    if (n <= 0) break;
                    s.Write(buf, 0, n);
                    len -= n;
                }
            }
        }

        static void SendArt(Stream s, Track t)
        {
            var tag = Tags.Read(t.path, true);
            if (tag.Picture != null && tag.Picture.Length > 0)
            {
                WriteHead(s, 200, tag.PictureMime ?? "image/jpeg", tag.Picture.Length, "Cache-Control: max-age=86400\r\n");
                s.Write(tag.Picture, 0, tag.Picture.Length);
                return;
            }
            // No embedded art: cover.jpg / folder.jpg next to the file.
            foreach (var n in new[] { "cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png" })
            {
                string f = Path.Combine(Path.GetDirectoryName(t.path), n);
                if (!File.Exists(f)) continue;
                var b = File.ReadAllBytes(f);
                WriteHead(s, 200, n.EndsWith("png") ? "image/png" : "image/jpeg", b.Length, "Cache-Control: max-age=86400\r\n");
                s.Write(b, 0, b.Length);
                return;
            }
            Send(s, 404, "text/plain", "no art");
        }

        // --- Discovery: the device broadcasts "DUALAMP?" and gets our port and name back -----------

        public static void StartDiscovery()
        {
            new Thread(() =>
            {
                try
                {
                    var udp = new UdpClient();
                    udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                    udp.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
                    var reply = Encoding.UTF8.GetBytes(Json.Serialize(new Dictionary<string, object> { { "app", "dualamp-server" }, { "name", PcName }, { "port", HttpPort } }));
                    while (true)
                    {
                        var from = new IPEndPoint(IPAddress.Any, 0);
                        var msg = udp.Receive(ref from);
                        if (Encoding.UTF8.GetString(msg).Trim() == "DUALAMP?") udp.Send(reply, reply.Length, from);
                    }
                }
                catch (Exception e) { Log("Automatic discovery is off (" + e.Message + "); enter this PC's address on your device instead."); }
            }) { IsBackground = true, Name = "discovery" }.Start();
        }

        /// <summary>This PC's addresses on the network, for "Enter PC address" on the device.</summary>
        public static List<string> Addresses()
        {
            var list = new List<string>();
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    foreach (var a in ni.GetIPProperties().UnicastAddresses)
                        if (a.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(a.Address + ":" + HttpPort);
                }
            }
            catch (Exception) { }
            return list;
        }
    }
}
