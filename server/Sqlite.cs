// Reads foobar2000's media library databases with Windows' own SQLite (winsqlite3.dll, part of Windows 10/11),
// so DualAmp Server needs no SQLite of its own.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

namespace DualAmp
{
    static class Sqlite
    {
        const int SQLITE_OK = 0, SQLITE_ROW = 100, SQLITE_OPEN_READONLY = 1;

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_open_v2(byte[] filename, out IntPtr db, int flags, IntPtr vfs);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_close(IntPtr db);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_prepare_v2(IntPtr db, byte[] sql, int bytes, out IntPtr stmt, IntPtr tail);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_step(IntPtr stmt);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_finalize(IntPtr stmt);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_column_count(IntPtr stmt);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern IntPtr sqlite3_column_text(IntPtr stmt, int col);
        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.StdCall)]
        static extern int sqlite3_column_bytes(IntPtr stmt, int col);

        static byte[] Utf8z(string s) { return Encoding.UTF8.GetBytes(s + "\0"); }

        /// <summary>Runs a query on a database file (opened read-only) and returns its rows as text.</summary>
        public static List<string[]> Query(string file, string sql)
        {
            var rows = new List<string[]>();
            IntPtr db;
            if (sqlite3_open_v2(Utf8z(file), out db, SQLITE_OPEN_READONLY, IntPtr.Zero) != SQLITE_OK)
                throw new Exception("can't open " + file);
            try
            {
                IntPtr stmt;
                if (sqlite3_prepare_v2(db, Utf8z(sql), -1, out stmt, IntPtr.Zero) != SQLITE_OK)
                    throw new Exception("query failed: " + sql);
                try
                {
                    int cols = sqlite3_column_count(stmt);
                    while (sqlite3_step(stmt) == SQLITE_ROW)
                    {
                        var row = new string[cols];
                        for (int c = 0; c < cols; c++)
                        {
                            IntPtr p = sqlite3_column_text(stmt, c);
                            int n = sqlite3_column_bytes(stmt, c);
                            if (p == IntPtr.Zero) continue;
                            var bytes = new byte[n];
                            Marshal.Copy(p, bytes, 0, n);
                            row[c] = Encoding.UTF8.GetString(bytes);
                        }
                        rows.Add(row);
                    }
                }
                finally { sqlite3_finalize(stmt); }
            }
            finally { sqlite3_close(db); }
            return rows;
        }
    }
}
