// DualAmp Server's window: first-launch music choice, the music it shares (add / change / remove folders,
// foobar2000's library on or off), reading progress, the log, and how to connect your device.
// Closing the window stops sharing.
using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("DualAmp Server")]
[assembly: AssemblyDescription("DualAmp Server - stream your PC music to DualAmp")]
[assembly: AssemblyProduct("DualAmp Server")]
[assembly: AssemblyCompany("njrozen")]
[assembly: AssemblyCopyright("njrozen and Claude")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace DualAmp
{
    static class Program
    {
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindow(string cls, string title);

        [STAThread]
        static void Main()
        {
            bool first;
            using (new Mutex(true, "DualAmpServer.SingleInstance", out first))
            {
                if (!first)
                {
                    // Already running: bring its window forward.
                    IntPtr h = FindWindow(null, "DualAmp Server");
                    if (h != IntPtr.Zero) { ShowWindow(h, 9); SetForegroundWindow(h); }
                    return;
                }
                try { SetProcessDPIAware(); } catch (Exception) { }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Server.LoadConfig();
                try { Server.StartHttp(); }
                catch (Exception e)
                {
                    MessageBox.Show("DualAmp Server can't use port " + Server.HttpPort + " (" + e.Message + "). Is another copy running?",
                        "DualAmp Server", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return;
                }
                Server.StartDiscovery();
                Application.Run(new MainWindow());
            }
        }
    }

    class MainWindow : Form
    {
        static readonly Color Bg = Color.FromArgb(0x12, 0x0F, 0x1F), Card = Color.FromArgb(0x1B, 0x17, 0x30),
            Line = Color.FromArgb(0x2E, 0x27, 0x50), Text1 = Color.FromArgb(0xF2, 0xF0, 0xF7), Dim = Color.FromArgb(0xA9, 0xA3, 0xBD),
            Accent = Color.FromArgb(0x1D, 0xB9, 0x54), Violet = Color.FromArgb(0xB3, 0x88, 0xFF);

        readonly Label status = new Label(), progressText = new Label(), progressFile = new Label(), address = new Label();
        readonly ProgressBar bar = new ProgressBar();
        readonly ListView sources = new ListView();
        readonly TextBox log = new TextBox();
        readonly Panel setup = new Panel(), main = new Panel();
        readonly Button useFoobar = new Button(), chooseFolder = new Button(), addFolder = new Button(), changeFolder = new Button(),
            removeFolder = new Button(), foobarToggle = new Button(), rescan = new Button();
        readonly Label setupFoobarInfo = new Label();
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer { Interval = 300 };
        bool lastSetup;

        // The screen's DPI, read before laying out (DeviceDpi still says 96 until the window exists).
        readonly float dpi;

        public MainWindow()
        {
            using (var g = CreateGraphics()) dpi = g.DpiX;
            Text = "DualAmp Server";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }
            BackColor = Bg; ForeColor = Text1;
            Font = new Font("Segoe UI", 10f);
            AutoScaleMode = AutoScaleMode.None; // sizes are scaled by hand (Px)
            ClientSize = Px(760, 720);
            MinimumSize = new Size(Px(560), Px(560));
            StartPosition = FormStartPosition.CenterScreen;
            Padding = new Padding(Px(20));

            // Header.
            var header = new Panel { Dock = DockStyle.Top, Height = Px(64), BackColor = Bg };
            var logo = new PictureBox { Size = new Size(Px(48), Px(48)), Location = new Point(0, Px(4)), SizeMode = PictureBoxSizeMode.Zoom };
            try { using (var st = Assembly.GetExecutingAssembly().GetManifestResourceStream("icon.png")) logo.Image = Image.FromStream(st); } catch (Exception) { }
            var title = new Label { Text = "DualAmp Server", Font = new Font("Segoe UI Semibold", 16f), AutoSize = true, Location = new Point(Px(60), Px(2)) };
            var sub = new Label { Text = Server.PcName + " · version " + Server.Version, ForeColor = Dim, AutoSize = true, Location = new Point(Px(62), Px(36)) };
            status.AutoSize = true; status.Anchor = AnchorStyles.Top | AnchorStyles.Right; status.ForeColor = Accent;
            status.Font = new Font("Segoe UI Semibold", 10.5f);
            header.Controls.AddRange(new Control[] { logo, title, sub, status });
            header.Resize += (s, e) => status.Location = new Point(header.Width - status.Width, Px(16));

            // First launch: choose the music.
            setup.Dock = DockStyle.Top; setup.Height = Px(250); setup.BackColor = Bg;
            var setupTitle = Heading("CHOOSE YOUR MUSIC", 0);
            Style(useFoobar, "Use foobar2000's library", true);
            useFoobar.Location = new Point(0, Px(34)); useFoobar.Size = new Size(Px(300), Px(40));
            setupFoobarInfo.ForeColor = Dim; setupFoobarInfo.Location = new Point(0, Px(80)); setupFoobarInfo.Size = new Size(Px(700), Px(44));
            Style(chooseFolder, "Choose a music folder…", true);
            chooseFolder.Location = new Point(0, Px(130)); chooseFolder.Size = new Size(Px(300), Px(40));
            var setupNote = new Label { Text = "The folder that holds your music (subfolders are included). Your choice is saved for next time;\nyou can add or change folders afterwards.", ForeColor = Dim, Location = new Point(0, Px(178)), Size = new Size(Px(720), Px(48)) };
            setup.Controls.AddRange(new Control[] { setupTitle, useFoobar, setupFoobarInfo, chooseFolder, setupNote });
            useFoobar.Click += (s, e) => { Server.UseFoobar = true; Server.SetupDone = true; Server.SaveConfig(); Server.Log("Sharing foobar2000's library"); Server.Rescan(); Refresh2(); };
            chooseFolder.Click += (s, e) => AddFolder(null);

            // After setup: sources, buttons, progress, device, log.
            main.Dock = DockStyle.Fill; main.BackColor = Bg;
            var srcTitle = Heading("MUSIC SHARED WITH YOUR DEVICE", 0);
            sources.View = View.Details; sources.FullRowSelect = true; sources.HideSelection = false; sources.MultiSelect = false;
            sources.HeaderStyle = ColumnHeaderStyle.None; sources.BorderStyle = BorderStyle.FixedSingle;
            sources.BackColor = Card; sources.ForeColor = Text1;
            sources.Columns.Add("Source", Px(700));
            sources.Location = new Point(0, Px(28)); sources.Size = new Size(Px(720), Px(110));
            sources.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            sources.SelectedIndexChanged += (s, e) => UpdateButtons();
            sources.Resize += (s, e) => FitColumns();

            Style(addFolder, "Add a music folder…", true); Style(changeFolder, "Change…", false); Style(removeFolder, "Remove", false);
            Style(foobarToggle, "Share foobar2000", false); Style(rescan, "Rescan", false);
            var buttons = new FlowLayoutPanel { Location = new Point(0, Px(146)), Size = new Size(Px(720), Px(44)), BackColor = Bg, WrapContents = false };
            buttons.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            foreach (var b in new[] { addFolder, changeFolder, removeFolder, foobarToggle, rescan }) { b.AutoSize = true; b.Margin = new Padding(0, 0, Px(8), 0); buttons.Controls.Add(b); }
            addFolder.Click += (s, e) => AddFolder(null);
            changeFolder.Click += (s, e) => { var f = SelectedFolder(); if (f != null) AddFolder(f); };
            removeFolder.Click += (s, e) =>
            {
                var f = SelectedFolder();
                if (f == null) return;
                Server.Folders.Remove(f); Server.SaveConfig(); Server.Log("Removed " + f); Server.Rescan(); Refresh2();
            };
            foobarToggle.Click += (s, e) =>
            {
                Server.UseFoobar = !Server.UseFoobar;
                if (Server.UseFoobar) Server.SetupDone = true;
                Server.SaveConfig();
                Server.Log(Server.UseFoobar ? "Sharing foobar2000's library" : "Stopped sharing foobar2000's library");
                Server.Rescan(); Refresh2();
            };
            rescan.Click += (s, e) => { Server.Log("Rescanning"); Server.Rescan(); };

            var progTitle = Heading("READING YOUR MUSIC", Px(204));
            progressText.Location = new Point(0, Px(230)); progressText.AutoSize = true;
            bar.Location = new Point(0, Px(256)); bar.Size = new Size(Px(720), Px(8)); bar.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            bar.Style = ProgressBarStyle.Continuous; bar.Maximum = 1000;
            progressFile.Location = new Point(0, Px(270)); progressFile.Size = new Size(Px(720), Px(22)); progressFile.ForeColor = Dim;
            progressFile.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right; progressFile.AutoEllipsis = true;

            var deviceTitle = Heading("ON YOUR DEVICE", Px(304));
            address.Location = new Point(0, Px(330)); address.Size = new Size(Px(720), Px(68)); address.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;

            var logTitle = Heading("LOG", Px(404));
            log.Multiline = true; log.ReadOnly = true; log.ScrollBars = ScrollBars.Vertical; log.BorderStyle = BorderStyle.FixedSingle;
            log.BackColor = Card; log.ForeColor = Dim; log.Font = new Font("Consolas", 9f);
            log.Location = new Point(0, Px(430)); log.Size = new Size(Px(720), Px(180));
            log.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            main.Controls.AddRange(new Control[] { srcTitle, sources, buttons, progTitle, progressText, bar, progressFile, deviceTitle, address, logTitle, log });

            var footer = new Label { Dock = DockStyle.Bottom, Height = Px(28), ForeColor = Dim, TextAlign = ContentAlignment.BottomLeft,
                Text = "Keep this window open while you listen. Closing it stops sharing." };

            Controls.Add(main); Controls.Add(setup); Controls.Add(header); Controls.Add(footer);
            Server.Log("DualAmp Server " + Server.Version + " on " + Server.PcName + ": " + string.Join(", ", Server.Addresses()));
            Server.Foobar = Server.ReadFoobar();
            if (Server.SetupDone) Server.Rescan(); else Server.Log("Choose your music in this window to start sharing.");
            lastSetup = !Server.SetupDone;
            Refresh2();
            timer.Tick += (s, e) => Tick();
            timer.Start();
            // Pick up library changes now and then.
            var periodic = new System.Windows.Forms.Timer { Interval = 15 * 60 * 1000 };
            periodic.Tick += (s, e) => { if (Server.SetupDone) Server.Rescan(); };
            periodic.Start();
        }

        int Px(int v) { return (int)Math.Round(v * dpi / 96.0); }
        Size Px(int w, int h) { return new Size(Px(w), Px(h)); }

        Label Heading(string text, int y)
        {
            return new Label { Text = text, ForeColor = Accent, Font = new Font("Segoe UI Semibold", 9f), AutoSize = true, Location = new Point(0, y) };
        }

        void Style(Button b, string text, bool primary)
        {
            b.Text = text; b.FlatStyle = FlatStyle.Flat; b.FlatAppearance.BorderSize = 0; b.Cursor = Cursors.Hand;
            b.BackColor = primary ? Accent : Line; b.ForeColor = primary ? Color.FromArgb(0x07, 0x15, 0x0C) : Text1;
            b.Font = new Font("Segoe UI Semibold", 10f); b.Padding = new Padding(Px(8), Px(4), Px(8), Px(4));
            b.Height = Px(36);
        }

        string SelectedFolder()
        {
            if (sources.SelectedItems.Count == 0) return null;
            return sources.SelectedItems[0].Tag as string;
        }

        void AddFolder(string replace)
        {
            using (var d = new FolderBrowserDialog { Description = "Choose the folder that holds your music. DualAmp shares it with your device over Wi-Fi.", ShowNewFolderButton = false })
            {
                if (replace != null && Directory.Exists(replace)) d.SelectedPath = replace;
                if (d.ShowDialog(this) != DialogResult.OK) return;
                string folder = d.SelectedPath;
                Server.Folders.RemoveAll(f => string.Equals(f.TrimEnd('\\'), folder.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase));
                int i = replace == null ? -1 : Server.Folders.IndexOf(replace);
                if (i >= 0) Server.Folders[i] = folder; else Server.Folders.Add(folder);
                Server.SetupDone = true;
                Server.SaveConfig();
                Server.Log(replace != null ? "Changed " + replace + " to " + folder : "Added " + folder);
                Server.Rescan();
                Refresh2();
            }
        }

        /// <summary>Rebuilds the setup / sources parts after a change.</summary>
        void Refresh2()
        {
            var fb = Server.Foobar;
            setup.Visible = !Server.SetupDone;
            main.Visible = Server.SetupDone;
            bool fbOk = fb.Installed && fb.Files.Count > 0 && fb.AnyReadable;
            useFoobar.Visible = setupFoobarInfo.Visible = fbOk;
            setupFoobarInfo.Text = fbOk ? "foobar2000 is installed: " + fb.Files.Count.ToString("N0") + " songs in " + string.Join(", ", fb.Folders) : "";

            string selected = SelectedFolder();
            sources.BeginUpdate();
            sources.Items.Clear();
            if (fb.Installed)
            {
                var item = new ListViewItem(FoobarRowText());
                item.ForeColor = Server.UseFoobar ? Text1 : Dim;
                sources.Items.Add(item);
            }
            foreach (var f in Server.Folders)
            {
                var item = new ListViewItem(Directory.Exists(f) ? f : f + "  ·  can't open this folder") { Tag = f };
                sources.Items.Add(item);
                if (f == selected) item.Selected = true;
            }
            if (sources.Items.Count == 0) sources.Items.Add(new ListViewItem("Nothing is shared. Add the folder that holds your music.") { ForeColor = Dim });
            sources.EndUpdate();
            foobarToggle.Visible = fb.Installed;
            foobarToggle.Text = Server.UseFoobar ? "Don't share foobar2000" : "Share foobar2000";
            var ips = Server.Addresses();
            address.Text = "DualAmp > Settings > PC music > Find PC on Wi-Fi. Or Enter PC address: " +
                (ips.Count > 0 ? string.Join(" or ", ips) : "(not connected to a network)") +
                "\nYour device and this PC must be on the same Wi-Fi. If your device can't find this PC, allow DualAmp Server in\nWindows Firewall (Windows asks the first time) or set your Wi-Fi network to Private in Windows settings.";
            FitColumns();
            UpdateButtons();
        }

        /// <summary>The one column takes the whole width.</summary>
        void FitColumns()
        {
            if (sources.Columns.Count > 0) sources.Columns[0].Width = Math.Max(Px(200), sources.ClientSize.Width - 4);
        }

        static string FoobarRowText()
        {
            var fb = Server.Foobar;
            return "foobar2000's library  ·  " + (Server.UseFoobar ? fb.Files.Count.ToString("N0") + " songs" : "not shared") + "  ·  " +
                (fb.Folders.Count > 0 ? string.Join(", ", fb.Folders) : "no folders set in foobar2000");
        }

        void UpdateButtons()
        {
            bool folder = SelectedFolder() != null;
            changeFolder.Enabled = removeFolder.Enabled = folder;
            changeFolder.ForeColor = removeFolder.ForeColor = folder ? Text1 : Dim;
        }

        void Tick()
        {
            if (lastSetup != Server.SetupDone) { lastSetup = Server.SetupDone; Refresh2(); }
            // Log lines from the background threads.
            lock (Server.PendingLog)
            {
                if (Server.PendingLog.Count > 0)
                {
                    log.AppendText(string.Join(Environment.NewLine, Server.PendingLog.ToArray()) + Environment.NewLine);
                    Server.PendingLog.Clear();
                }
            }
            int phase = Server.Phase, done = Server.Done, total = Server.Total, count = Server.TrackCount;
            if (!Server.SetupDone) status.Text = "Not sharing yet";
            else if (phase != 0) status.Text = "Reading music…";
            else status.Text = "Sharing " + count.ToString("N0") + " songs";
            status.ForeColor = Server.SetupDone && phase == 0 ? Accent : Dim;
            status.Location = new Point(status.Parent.Width - status.Width, Px(16));
            if (phase == 2 && total > 0)
            {
                bar.Style = ProgressBarStyle.Continuous;
                bar.Value = Math.Min(1000, (int)(1000L * done / total));
                progressText.Text = "Reading " + done.ToString("N0") + " of " + total.ToString("N0") + " files";
                progressFile.Text = Server.Current;
            }
            else if (phase == 1)
            {
                bar.Style = ProgressBarStyle.Marquee;
                progressText.Text = "Finding your music files…";
                progressFile.Text = Server.Current;
            }
            else
            {
                bar.Style = ProgressBarStyle.Continuous;
                bar.Value = count > 0 ? 1000 : 0;
                progressText.Text = "Done: " + count.ToString("N0") + " songs are shared.";
                progressFile.Text = "";
                // A scan just finished: song counts for foobar2000 may have changed.
                if (sources.Items.Count > 0 && Server.Foobar.Installed && sources.Items[0].Text != FoobarRowText()) Refresh2();
            }
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            Server.Log("Window closed: stopped sharing.");
            base.OnFormClosed(e);
            Environment.Exit(0);
        }
    }
}
