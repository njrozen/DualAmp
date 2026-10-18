// Reads song details (title, artist, album, ...), length and album art from music files:
// MP3 (ID3v2 / ID3v1), FLAC, M4A / MP4 (iTunes tags), OGG Vorbis and Opus, plus WAV length.
// Damaged or unknown files simply give fewer details; the server fills in the rest from the file name.
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace DualAmp
{
    class TagInfo
    {
        public string Title = "", Artist = "", AlbumArtist = "", Album = "", Genre = "";
        public int Year, Track, Disc, Bitrate;
        public long DurationMs;
        public byte[] Picture;
        public string PictureMime;
    }

    static class Tags
    {
        /// <summary>Reads the details of [path]; with [wantPicture], also its embedded album art.</summary>
        public static TagInfo Read(string path, bool wantPicture)
        {
            var t = new TagInfo();
            try
            {
                using (var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    switch (Path.GetExtension(path).ToLowerInvariant())
                    {
                        case ".mp3": ReadMp3(f, t, wantPicture); break;
                        case ".flac": ReadFlac(f, t, wantPicture); break;
                        case ".m4a": case ".mp4": case ".aac": case ".alac": ReadMp4(f, t, wantPicture); break;
                        case ".ogg": case ".opus": case ".oga": ReadOgg(f, t, wantPicture); break;
                        case ".wav": ReadWav(f, t); break;
                    }
                    if (t.Bitrate == 0 && t.DurationMs > 0) t.Bitrate = (int)(f.Length * 8000 / t.DurationMs);
                }
            }
            catch (Exception) { }
            return t;
        }

        // --- Helpers ----------------------------------------------------------------------------

        static byte[] ReadBytes(Stream f, int n)
        {
            var b = new byte[n];
            int got = 0;
            while (got < n)
            {
                int r = f.Read(b, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            if (got < n) Array.Resize(ref b, got);
            return b;
        }

        static int BE32(byte[] b, int o) { return (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]; }
        static int BE24(byte[] b, int o) { return (b[o] << 16) | (b[o + 1] << 8) | b[o + 2]; }
        static int BE16(byte[] b, int o) { return (b[o] << 8) | b[o + 1]; }
        static int LE32(byte[] b, int o) { return b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24); }
        static long LE64(byte[] b, int o) { return (long)(uint)LE32(b, o) | ((long)(uint)LE32(b, o + 4) << 32); }
        static int Syncsafe(byte[] b, int o) { return (b[o] << 21) | (b[o + 1] << 14) | (b[o + 2] << 7) | b[o + 3]; }

        /// <summary>"3/12" -> 3; "2009-05-01" -> 2009.</summary>
        static int LeadingNumber(string s)
        {
            int n = 0, digits = 0;
            foreach (char c in (s ?? "").Trim())
            {
                if (c < '0' || c > '9') break;
                n = n * 10 + (c - '0');
                if (++digits > 9) break;
            }
            return n;
        }

        static string Clean(string s) { return (s ?? "").Replace("\0", "").Trim(); }

        /// <summary>"A / B" -> "A" (names with commas, like "Tyler, The Creator", stay whole).</summary>
        static string FirstValue(string s, string separator)
        {
            int i = s.IndexOf(separator);
            return (i > 0 ? s.Substring(0, i) : s).Trim();
        }

        // --- MP3 --------------------------------------------------------------------------------

        static readonly string[] Genres = {
            "Blues","Classic Rock","Country","Dance","Disco","Funk","Grunge","Hip-Hop","Jazz","Metal","New Age","Oldies",
            "Other","Pop","R&B","Rap","Reggae","Rock","Techno","Industrial","Alternative","Ska","Death Metal","Pranks",
            "Soundtrack","Euro-Techno","Ambient","Trip-Hop","Vocal","Jazz+Funk","Fusion","Trance","Classical","Instrumental",
            "Acid","House","Game","Sound Clip","Gospel","Noise","Alternative Rock","Bass","Soul","Punk","Space","Meditative",
            "Instrumental Pop","Instrumental Rock","Ethnic","Gothic","Darkwave","Techno-Industrial","Electronic","Pop-Folk",
            "Eurodance","Dream","Southern Rock","Comedy","Cult","Gangsta","Top 40","Christian Rap","Pop/Funk","Jungle",
            "Native American","Cabaret","New Wave","Psychedelic","Rave","Showtunes","Trailer","Lo-Fi","Tribal","Acid Punk",
            "Acid Jazz","Polka","Retro","Musical","Rock & Roll","Hard Rock",
        };

        /// <summary>ID3 genres may be "(17)", "17" or "(17)Rock".</summary>
        static string Genre(string g)
        {
            g = Clean(g);
            if (g.StartsWith("(") && g.Contains(")"))
            {
                string rest = g.Substring(g.IndexOf(')') + 1).Trim();
                if (rest.Length > 0) return rest;
                g = g.Substring(1, g.IndexOf(')') - 1);
            }
            int n;
            if (int.TryParse(g, out n) && n >= 0 && n < Genres.Length) return Genres[n];
            return g;
        }

        /// <summary>The first of the values in a text frame (they are separated by nulls).</summary>
        static string Id3First(byte[] b, int o, int len)
        {
            return Id3Text(b, o, len, true);
        }

        static string Id3Text(byte[] b, int o, int len) { return Id3Text(b, o, len, false); }

        static string Id3Text(byte[] b, int o, int len, bool firstOnly)
        {
            if (len <= 1) return "";
            byte enc = b[o];
            string s;
            switch (enc)
            {
                case 1: s = DecodeUtf16(b, o + 1, len - 1, false); break;
                case 2: s = Encoding.BigEndianUnicode.GetString(b, o + 1, len - 1); break;
                case 3: s = Encoding.UTF8.GetString(b, o + 1, len - 1); break;
                default: s = Encoding.GetEncoding(28591).GetString(b, o + 1, len - 1); break;
            }
            // Several values are separated by nulls.
            var parts = new List<string>();
            foreach (var p in s.Split('\0')) if (p.Trim().Length > 0 && !parts.Contains(p.Trim())) parts.Add(p.Trim());
            if (firstOnly) return parts.Count > 0 ? parts[0] : "";
            return string.Join(", ", parts);
        }

        static string DecodeUtf16(byte[] b, int o, int len, bool bigEndianDefault)
        {
            if (len >= 2 && b[o] == 0xFF && b[o + 1] == 0xFE) return Encoding.Unicode.GetString(b, o + 2, (len - 2) & ~1);
            if (len >= 2 && b[o] == 0xFE && b[o + 1] == 0xFF) return Encoding.BigEndianUnicode.GetString(b, o + 2, (len - 2) & ~1);
            return (bigEndianDefault ? Encoding.BigEndianUnicode : Encoding.Unicode).GetString(b, o, len & ~1);
        }

        /// <summary>Skips a null-terminated string in [enc] (2-byte nulls for UTF-16) and returns where it ends.</summary>
        static int SkipString(byte[] b, int o, int end, int enc)
        {
            if (enc == 1 || enc == 2)
            {
                while (o + 1 < end && !(b[o] == 0 && b[o + 1] == 0)) o += 2;
                return Math.Min(o + 2, end);
            }
            while (o < end && b[o] != 0) o++;
            return Math.Min(o + 1, end);
        }

        static void ReadMp3(FileStream f, TagInfo t, bool wantPicture)
        {
            long audioStart = 0;
            var head = ReadBytes(f, 10);
            if (head.Length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3')
            {
                int ver = head[3], flags = head[5], size = Syncsafe(head, 6);
                audioStart = 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
                var tag = ReadBytes(f, size);
                if (ver < 4 && (flags & 0x80) != 0) tag = Unsync(tag);
                ParseId3v2(tag, ver, flags, t, wantPicture);
            }
            if (t.Title.Length == 0 && f.Length > 128)
            {
                f.Seek(-128, SeekOrigin.End);
                var v1 = ReadBytes(f, 128);
                if (v1.Length == 128 && v1[0] == 'T' && v1[1] == 'A' && v1[2] == 'G')
                {
                    var latin = Encoding.GetEncoding(28591);
                    t.Title = Clean(latin.GetString(v1, 3, 30));
                    t.Artist = Clean(latin.GetString(v1, 33, 30));
                    t.Album = Clean(latin.GetString(v1, 63, 30));
                    t.Year = LeadingNumber(latin.GetString(v1, 93, 4));
                    if (v1[125] == 0 && v1[126] != 0) t.Track = v1[126];
                    if (v1[127] < Genres.Length) t.Genre = Genres[v1[127]];
                }
            }
            Mp3Length(f, audioStart, t);
        }

        static byte[] Unsync(byte[] b)
        {
            var o = new MemoryStream(b.Length);
            for (int i = 0; i < b.Length; i++)
            {
                o.WriteByte(b[i]);
                if (b[i] == 0xFF && i + 1 < b.Length && b[i + 1] == 0) i++;
            }
            return o.ToArray();
        }

        static void ParseId3v2(byte[] tag, int ver, int flags, TagInfo t, bool wantPicture)
        {
            int pos = 0;
            if ((flags & 0x40) != 0 && tag.Length >= 4)
                pos = ver == 4 ? Syncsafe(tag, 0) : BE32(tag, 0) + 4;
            int idLen = ver == 2 ? 3 : 4, headLen = ver == 2 ? 6 : 10;
            int pictureType = -1;
            while (pos + headLen <= tag.Length)
            {
                string id = Encoding.ASCII.GetString(tag, pos, idLen);
                if (id[0] == '\0') break;
                int size = ver == 2 ? BE24(tag, pos + 3) : ver == 4 ? Syncsafe(tag, pos + 4) : BE32(tag, pos + 4);
                int data = pos + headLen;
                if (size <= 0 || data + size > tag.Length) break;
                if (ver == 4 && (tag[pos + 9] & 0x02) != 0)
                {
                    // Frame-level unsynchronisation (ID3v2.4).
                    var raw = new byte[size];
                    Array.Copy(tag, data, raw, 0, size);
                    raw = Unsync(raw);
                    ApplyId3Frame(id, raw, 0, raw.Length, t, wantPicture, ref pictureType);
                }
                else ApplyId3Frame(id, tag, data, size, t, wantPicture, ref pictureType);
                pos = data + size;
            }
        }

        static void ApplyId3Frame(string id, byte[] b, int o, int size, TagInfo t, bool wantPicture, ref int pictureType)
        {
            switch (id)
            {
                case "TIT2": case "TT2": t.Title = Id3Text(b, o, size); break;
                // "A / B" or several values: the first artist, so features don't split an artist in the library.
                case "TPE1": case "TP1": t.Artist = FirstValue(Id3First(b, o, size), " / "); break;
                case "TPE2": case "TP2": t.AlbumArtist = Id3Text(b, o, size); break;
                case "TALB": case "TAL": t.Album = Id3Text(b, o, size); break;
                case "TCON": case "TCO": t.Genre = FirstValue(Genre(Id3First(b, o, size)), "/"); break;
                case "TYER": case "TYE": case "TDRC": if (t.Year == 0) t.Year = LeadingNumber(Id3Text(b, o, size)); break;
                case "TRCK": case "TRK": t.Track = LeadingNumber(Id3Text(b, o, size)); break;
                case "TPOS": case "TPA": t.Disc = LeadingNumber(Id3Text(b, o, size)); break;
                case "APIC": case "PIC":
                    if (!wantPicture || size < 4) break;
                    int end = o + size, enc = b[o], p = o + 1;
                    string mime;
                    if (id == "PIC")
                    {
                        string fmt = Encoding.ASCII.GetString(b, p, 3).ToUpperInvariant();
                        mime = fmt == "PNG" ? "image/png" : "image/jpeg";
                        p += 3;
                    }
                    else
                    {
                        int m = p;
                        while (p < end && b[p] != 0) p++;
                        mime = Encoding.ASCII.GetString(b, m, p - m);
                        p++;
                    }
                    if (p >= end) break;
                    int type = b[p++];
                    p = SkipString(b, p, end, enc);
                    // Prefer the front cover (type 3); otherwise the first picture.
                    if (p < end && (t.Picture == null || (type == 3 && pictureType != 3)))
                    {
                        t.Picture = new byte[end - p];
                        Array.Copy(b, p, t.Picture, 0, end - p);
                        t.PictureMime = mime.Contains("/") ? mime : "image/" + (mime.Length > 0 ? mime.ToLowerInvariant() : "jpeg");
                        pictureType = type;
                    }
                    break;
            }
        }

        static readonly int[] Mpeg1L3 = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0 };
        static readonly int[] Mpeg2L3 = { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0 };
        static readonly int[] Mpeg1L2 = { 0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0 };

        /// <summary>Length and bitrate from the first MPEG frame (and its Xing / VBRI header for variable bitrate).</summary>
        static void Mp3Length(FileStream f, long start, TagInfo t)
        {
            f.Seek(start, SeekOrigin.Begin);
            var b = ReadBytes(f, 64 * 1024);
            for (int i = 0; i + 4 < b.Length; i++)
            {
                if (b[i] != 0xFF || (b[i + 1] & 0xE0) != 0xE0) continue;
                int verBits = (b[i + 1] >> 3) & 3, layerBits = (b[i + 1] >> 1) & 3;
                int brIdx = b[i + 2] >> 4, srIdx = (b[i + 2] >> 2) & 3, chan = b[i + 3] >> 6;
                if (verBits == 1 || layerBits == 0 || brIdx == 0 || brIdx == 15 || srIdx == 3) continue;
                bool mpeg1 = verBits == 3;
                int[] rates = { 44100, 48000, 32000 };
                int sr = rates[srIdx] / (mpeg1 ? 1 : verBits == 2 ? 2 : 4);
                bool layer3 = layerBits == 1;
                int kbps = layer3 ? (mpeg1 ? Mpeg1L3 : Mpeg2L3)[brIdx] : Mpeg1L2[brIdx];
                int samplesPerFrame = layer3 && !mpeg1 ? 576 : 1152;
                // A real frame is followed by another one: skip stray bytes that only look like a frame header.
                int frameLen = (layer3 && !mpeg1 ? 72000 : 144000) * kbps / sr + ((b[i + 2] >> 1) & 1);
                if (frameLen < 24 || (i + frameLen + 1 < b.Length && (b[i + frameLen] != 0xFF || (b[i + frameLen + 1] & 0xE0) != 0xE0))) continue;
                // Xing / Info header (variable bitrate): total frames.
                int side = mpeg1 ? (chan == 3 ? 17 : 32) : (chan == 3 ? 9 : 17);
                int x = i + 4 + side;
                long frames = 0;
                if (x + 12 < b.Length)
                {
                    string tag = Encoding.ASCII.GetString(b, x, 4);
                    if ((tag == "Xing" || tag == "Info") && (BE32(b, x + 4) & 1) != 0) frames = (uint)BE32(b, x + 8);
                }
                int v = i + 4 + 32;
                if (frames == 0 && v + 18 < b.Length && Encoding.ASCII.GetString(b, v, 4) == "VBRI") frames = (uint)BE32(b, v + 14);
                long audioBytes = f.Length - start - i;
                if (frames > 0)
                {
                    t.DurationMs = frames * samplesPerFrame * 1000L / sr;
                    if (t.DurationMs > 0) t.Bitrate = (int)(audioBytes * 8000 / t.DurationMs);
                }
                else if (kbps > 0)
                {
                    t.Bitrate = kbps * 1000;
                    t.DurationMs = audioBytes * 8 / kbps;
                }
                return;
            }
        }

        // --- FLAC / Vorbis comments -------------------------------------------------------------

        static void ReadFlac(FileStream f, TagInfo t, bool wantPicture)
        {
            var head = ReadBytes(f, 10);
            if (head.Length < 4) return;
            // Some FLAC files start with an ID3v2 tag; skip it.
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3' && head.Length == 10) f.Seek(10 + Syncsafe(head, 6), SeekOrigin.Begin);
            else f.Seek(0, SeekOrigin.Begin);
            var magic = ReadBytes(f, 4);
            if (magic.Length < 4 || Encoding.ASCII.GetString(magic) != "fLaC") return;
            bool last = false;
            while (!last)
            {
                var bh = ReadBytes(f, 4);
                if (bh.Length < 4) return;
                last = (bh[0] & 0x80) != 0;
                int type = bh[0] & 0x7F, len = BE24(bh, 1);
                if (type == 0 && len >= 18)
                {
                    var si = ReadBytes(f, len);
                    int sr = (si[10] << 12) | (si[11] << 4) | (si[12] >> 4);
                    long total = ((long)(si[13] & 0x0F) << 32) | (uint)BE32(si, 14);
                    if (sr > 0) t.DurationMs = total * 1000 / sr;
                }
                else if (type == 4) VorbisComments(ReadBytes(f, len), 0, t, wantPicture);
                else if (type == 6 && wantPicture && t.Picture == null) FlacPicture(ReadBytes(f, len), t);
                else f.Seek(len, SeekOrigin.Current);
            }
        }

        static void FlacPicture(byte[] b, TagInfo t)
        {
            try
            {
                int p = 4, mimeLen = BE32(b, p); p += 4;
                string mime = Encoding.ASCII.GetString(b, p, mimeLen); p += mimeLen;
                int descLen = BE32(b, p); p += 4 + descLen + 16;
                int dataLen = BE32(b, p); p += 4;
                if (dataLen <= 0 || p + dataLen > b.Length) return;
                t.Picture = new byte[dataLen];
                Array.Copy(b, p, t.Picture, 0, dataLen);
                t.PictureMime = mime.Length > 0 ? mime : "image/jpeg";
            }
            catch (Exception) { }
        }

        /// <summary>A Vorbis comment block (FLAC, OGG Vorbis, Opus): vendor string, then "KEY=value" entries.</summary>
        static void VorbisComments(byte[] b, int p, TagInfo t, bool wantPicture)
        {
            try
            {
                p += 4 + LE32(b, p);
                int count = LE32(b, p); p += 4;
                for (int i = 0; i < count && p + 4 <= b.Length; i++)
                {
                    int len = LE32(b, p); p += 4;
                    if (len < 0 || p + len > b.Length) break;
                    string entry = Encoding.UTF8.GetString(b, p, len);
                    p += len;
                    int eq = entry.IndexOf('=');
                    if (eq <= 0) continue;
                    string key = entry.Substring(0, eq).ToUpperInvariant(), val = entry.Substring(eq + 1).Trim();
                    switch (key)
                    {
                        case "TITLE": t.Title = Join(t.Title, val); break;
                        case "ARTIST": t.Artist = Join(t.Artist, val); break;
                        case "ALBUMARTIST": case "ALBUM ARTIST": t.AlbumArtist = val; break;
                        case "ALBUM": t.Album = val; break;
                        case "GENRE": if (t.Genre.Length == 0) t.Genre = val; break;
                        case "DATE": case "YEAR": if (t.Year == 0) t.Year = LeadingNumber(val); break;
                        case "TRACKNUMBER": t.Track = LeadingNumber(val); break;
                        case "DISCNUMBER": t.Disc = LeadingNumber(val); break;
                        case "METADATA_BLOCK_PICTURE":
                            if (wantPicture && t.Picture == null) { try { FlacPicture(Convert.FromBase64String(val), t); } catch (Exception) { } }
                            break;
                    }
                }
            }
            catch (Exception) { }
        }

        static string Join(string a, string b) { return a.Length == 0 ? b : a + ", " + b; }

        // --- OGG Vorbis / Opus --------------------------------------------------------------------

        static void ReadOgg(FileStream f, TagInfo t, bool wantPicture)
        {
            // The first packets: identification header, then the comment header (may span pages).
            var packets = new List<byte[]>();
            var current = new MemoryStream();
            long read = 0;
            while (packets.Count < 2 && read < 16 * 1024 * 1024)
            {
                var ph = ReadBytes(f, 27);
                if (ph.Length < 27 || Encoding.ASCII.GetString(ph, 0, 4) != "OggS") break;
                var lacing = ReadBytes(f, ph[26]);
                read += 27 + lacing.Length;
                foreach (byte seg in lacing)
                {
                    var data = ReadBytes(f, seg);
                    read += seg;
                    current.Write(data, 0, data.Length);
                    if (seg < 255) { packets.Add(current.ToArray()); current = new MemoryStream(); if (packets.Count == 2) break; }
                }
            }
            if (packets.Count == 0) return;
            var id = packets[0];
            bool opus = id.Length >= 19 && Encoding.ASCII.GetString(id, 0, 8) == "OpusHead";
            int rate = 48000, preSkip = 0;
            if (opus) preSkip = id[10] | (id[11] << 8);
            else if (id.Length >= 16 && id[0] == 1) rate = LE32(id, 12);
            if (packets.Count > 1)
            {
                var c = packets[1];
                if (opus && c.Length > 8 && Encoding.ASCII.GetString(c, 0, 8) == "OpusTags") VorbisComments(c, 8, t, wantPicture);
                else if (!opus && c.Length > 7 && c[0] == 3) VorbisComments(c, 7, t, wantPicture);
            }
            // Length: the granule position of the last page.
            int tailLen = (int)Math.Min(f.Length, 128 * 1024);
            f.Seek(-tailLen, SeekOrigin.End);
            var tail = ReadBytes(f, tailLen);
            for (int i = tail.Length - 27; i >= 0; i--)
            {
                if (tail[i] != 'O' || tail[i + 1] != 'g' || tail[i + 2] != 'g' || tail[i + 3] != 'S') continue;
                long granule = LE64(tail, i + 6);
                if (granule > 0 && rate > 0) t.DurationMs = (granule - preSkip) * 1000 / rate;
                break;
            }
        }

        // --- MP4 / M4A --------------------------------------------------------------------------

        static void ReadMp4(FileStream f, TagInfo t, bool wantPicture)
        {
            // Find "moov" among the top-level boxes (skipping the audio data) and read it into memory.
            long pos = 0;
            while (pos + 8 <= f.Length)
            {
                f.Seek(pos, SeekOrigin.Begin);
                var h = ReadBytes(f, 16);
                if (h.Length < 8) return;
                long size = (uint)BE32(h, 0);
                int headLen = 8;
                if (size == 1 && h.Length >= 16) { size = ((long)(uint)BE32(h, 8) << 32) | (uint)BE32(h, 12); headLen = 16; }
                else if (size == 0) size = f.Length - pos;
                if (size < headLen) return;
                if (h[4] == 'm' && h[5] == 'o' && h[6] == 'o' && h[7] == 'v')
                {
                    if (size > 64 * 1024 * 1024) return;
                    f.Seek(pos + headLen, SeekOrigin.Begin);
                    var moov = ReadBytes(f, (int)(size - headLen));
                    Mp4Boxes(moov, 0, moov.Length, t, wantPicture, "");
                    return;
                }
                pos += size;
            }
        }

        static void Mp4Boxes(byte[] b, int start, int end, TagInfo t, bool wantPicture, string parent)
        {
            int p = start;
            while (p + 8 <= end)
            {
                int size = BE32(b, p);
                if (size < 8 || p + size > end) break;
                string name = Encoding.GetEncoding(28591).GetString(b, p + 4, 4);
                int body = p + 8;
                switch (name)
                {
                    case "udta": case "ilst": Mp4Boxes(b, body, p + size, t, wantPicture, name); break;
                    // "meta" is a full box: 4 bytes of version / flags before its children.
                    case "meta": Mp4Boxes(b, body + 4, p + size, t, wantPicture, name); break;
                    case "mvhd":
                        if (b[body] == 1 && body + 32 <= p + size)
                        {
                            long scale = (uint)BE32(b, body + 20);
                            long dur = ((long)(uint)BE32(b, body + 24) << 32) | (uint)BE32(b, body + 28);
                            if (scale > 0) t.DurationMs = dur * 1000 / scale;
                        }
                        else if (body + 20 <= p + size)
                        {
                            long scale = (uint)BE32(b, body + 12), dur = (uint)BE32(b, body + 16);
                            if (scale > 0) t.DurationMs = dur * 1000 / scale;
                        }
                        break;
                    default:
                        if (parent == "ilst") Mp4Item(name, b, body, p + size, t, wantPicture);
                        break;
                }
                p += size;
            }
        }

        /// <summary>An iTunes tag ("©nam", "trkn", "covr", ...) holding a "data" box.</summary>
        static void Mp4Item(string name, byte[] b, int start, int end, TagInfo t, bool wantPicture)
        {
            int p = start;
            while (p + 16 <= end)
            {
                int size = BE32(b, p);
                if (size < 16 || p + size > end) return;
                if (b[p + 4] == 'd' && b[p + 5] == 'a' && b[p + 6] == 't' && b[p + 7] == 'a')
                {
                    int type = BE32(b, p + 8) & 0xFFFFFF, v = p + 16, len = size - 16;
                    string text = len > 0 ? Encoding.UTF8.GetString(b, v, len) : "";
                    switch (name)
                    {
                        case "©nam": t.Title = text; break;
                        case "©ART": t.Artist = text; break;
                        case "aART": t.AlbumArtist = text; break;
                        case "©alb": t.Album = text; break;
                        case "©gen": t.Genre = text; break;
                        case "gnre": if (len >= 2 && t.Genre.Length == 0) { int g = BE16(b, v) - 1; if (g >= 0 && g < Genres.Length) t.Genre = Genres[g]; } break;
                        case "©day": t.Year = LeadingNumber(text); break;
                        case "trkn": if (len >= 4) t.Track = BE16(b, v + 2); break;
                        case "disk": if (len >= 4) t.Disc = BE16(b, v + 2); break;
                        case "covr":
                            if (wantPicture && t.Picture == null && len > 0)
                            {
                                t.Picture = new byte[len];
                                Array.Copy(b, v, t.Picture, 0, len);
                                t.PictureMime = type == 14 ? "image/png" : "image/jpeg";
                            }
                            break;
                    }
                    return;
                }
                p += size;
            }
        }

        // --- WAV ----------------------------------------------------------------------------------

        static void ReadWav(FileStream f, TagInfo t)
        {
            var b = ReadBytes(f, 64 * 1024);
            if (b.Length < 12 || Encoding.ASCII.GetString(b, 0, 4) != "RIFF") return;
            int p = 12, byteRate = 0;
            while (p + 8 <= b.Length)
            {
                string id = Encoding.ASCII.GetString(b, p, 4);
                int len = LE32(b, p + 4);
                if (id == "fmt " && p + 16 <= b.Length) byteRate = LE32(b, p + 16);
                if (id == "data") { if (byteRate > 0) t.DurationMs = (uint)len * 1000L / byteRate; return; }
                p += 8 + len + (len & 1);
            }
        }
    }
}
