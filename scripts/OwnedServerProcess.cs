using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace Xlm.Launcher
{
    // A callback can inspect identity/liveness, but cannot replace the retained handle.
    public sealed class ServerProcessView
    {
        private readonly OwnedServerProcess owner;
        internal ServerProcessView(OwnedServerProcess owner) { this.owner = owner; }
        public int Id { get { return owner.Id; } }
        public DateTime StartTimeUtc { get { return owner.StartTimeUtc; } }
        public bool HasExited { get { return owner.HasExited; } }
    }

    public sealed class OwnedServerProcess : IDisposable
    {
        private IntPtr job;
        private IntPtr process;
        private bool exitConfirmed;
        public int Id { get; private set; }
        public DateTime StartTimeUtc { get; private set; }
        public ServerProcessView View { get; private set; }
        internal bool HasExited
        {
            get
            {
                if (process == IntPtr.Zero) return exitConfirmed;
                uint result = WaitForSingleObject(process, 0);
                if (result == 0) return true;
                if (result == 258) return false;
                throw Error("Inspect owned server process");
            }
        }

        private OwnedServerProcess() { View = new ServerProcessView(this); }

        public static OwnedServerProcess Start(string executable, string[] arguments, string workingDirectory)
        {
            var owned = new OwnedServerProcess();
            IntPtr attributes = IntPtr.Zero, jobValue = IntPtr.Zero;
            bool initialized = false;
            try
            {
                // Unnamed and non-inheritable: only this verifier holds the job handle.
                owned.job = CreateJobObject(IntPtr.Zero, null);
                if (owned.job == IntPtr.Zero) throw Error("Create verification job");
                var limits = new ExtendedLimitInformation();
                limits.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                if (!SetInformationJobObject(owned.job, 9, ref limits, (uint)Marshal.SizeOf(typeof(ExtendedLimitInformation))))
                    throw Error("Configure verification job");

                IntPtr bytes = IntPtr.Zero;
                InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref bytes);
                if (bytes == IntPtr.Zero) throw Error("Measure process attributes");
                attributes = Marshal.AllocHGlobal(bytes);
                if (!InitializeProcThreadAttributeList(attributes, 1, 0, ref bytes)) throw Error("Initialize process attributes");
                initialized = true;
                jobValue = Marshal.AllocHGlobal(IntPtr.Size);
                Marshal.WriteIntPtr(jobValue, owned.job);
                // PROC_THREAD_ATTRIBUTE_JOB_LIST assigns the job atomically at creation.
                // No child runs during an unowned create-then-assign interval.
                if (!UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x0002000D), jobValue,
                    new IntPtr(IntPtr.Size), IntPtr.Zero, IntPtr.Zero)) throw Error("Assign verification job attribute");

                var startup = new StartupInfoEx();
                startup.StartupInfo.cb = Marshal.SizeOf(typeof(StartupInfoEx));
                startup.AttributeList = attributes;
                var command = new StringBuilder(Quote(executable));
                foreach (string argument in arguments) command.Append(' ').Append(Quote(argument));
                ProcessInformation created;
                if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, false,
                    0x00080000 | 0x08000000, IntPtr.Zero, workingDirectory, ref startup, out created))
                    throw Error("Start verification-owned server");
                owned.process = created.Process;
                owned.Id = unchecked((int)created.ProcessId);
                CloseHandle(created.Thread);
                long creation, exit, kernel, user;
                if (!GetProcessTimes(owned.process, out creation, out exit, out kernel, out user))
                    throw Error("Read owned process creation time");
                owned.StartTimeUtc = DateTime.FromFileTimeUtc(creation);
                return owned;
            }
            catch (Exception startupFailure)
            {
                try { owned.Dispose(); }
                catch (Exception cleanupFailure) { throw new AggregateException("Server startup and cleanup failed", startupFailure, cleanupFailure); }
                throw;
            }
            finally
            {
                if (initialized) DeleteProcThreadAttributeList(attributes);
                if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
                if (jobValue != IntPtr.Zero) Marshal.FreeHGlobal(jobValue);
            }
        }

        public void Dispose()
        {
            Exception closeFailure = null;
            if (job != IntPtr.Zero)
            {
                if (!CloseHandle(job)) closeFailure = Error("Close verification job");
                else job = IntPtr.Zero;
            }
            if (process != IntPtr.Zero)
            {
                uint result = WaitForSingleObject(process, 10000);
                if (result == 0) exitConfirmed = true;
                Exception waitFailure = result == 0 ? null : result == 258
                    ? (Exception)new TimeoutException("Owned server did not exit within ten seconds of cleanup")
                    : Error("Wait for owned server cleanup");
                if (exitConfirmed) { CloseHandle(process); process = IntPtr.Zero; }
                if (closeFailure != null && waitFailure != null)
                    throw new AggregateException("Owned server cleanup failed", closeFailure, waitFailure);
                if (waitFailure != null) throw waitFailure;
            }
            if (closeFailure != null) throw closeFailure;
        }

        // Windows CRT argument quoting, including trailing backslashes and embedded quotes.
        private static string Quote(string value)
        {
            var result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char character in value)
            {
                if (character == '\\') { slashes++; continue; }
                if (character == '"') result.Append('\\', slashes * 2 + 1).Append('"');
                else result.Append('\\', slashes).Append(character);
                slashes = 0;
            }
            return result.Append('\\', slashes * 2).Append('"').ToString();
        }
        private static Win32Exception Error(string operation) { return new Win32Exception(Marshal.GetLastWin32Error(), operation + " failed"); }

        [StructLayout(LayoutKind.Sequential)] private struct BasicLimitInformation
        {
            public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)] private struct IoCounters
        { public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes; }
        [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimitInformation
        {
            public BasicLimitInformation BasicLimitInformation;
            public IoCounters IoInfo;
            public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
        }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct StartupInfo
        {
            public int cb;
            public string Reserved, Desktop, Title;
            public uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
            public ushort ShowWindow, ReservedSize;
            public IntPtr ReservedBytes, StandardInput, StandardOutput, StandardError;
        }
        [StructLayout(LayoutKind.Sequential)] private struct StartupInfoEx
        { public StartupInfo StartupInfo; public IntPtr AttributeList; }
        [StructLayout(LayoutKind.Sequential)] private struct ProcessInformation
        { public IntPtr Process, Thread; public uint ProcessId, ThreadId; }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr CreateJobObject(IntPtr security, string name);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimitInformation information, uint length);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attribute, IntPtr value, IntPtr size, IntPtr previous, IntPtr returnSize);
        [DllImport("kernel32.dll")] private static extern void DeleteProcThreadAttributeList(IntPtr list);
        [DllImport("kernel32.dll", EntryPoint = "CreateProcessW", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool CreateProcess(string application, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity, bool inheritHandles, uint flags, IntPtr environment, string currentDirectory, ref StartupInfoEx startup, out ProcessInformation information);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetProcessTimes(IntPtr process, out long creation, out long exit, out long kernel, out long user);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool CloseHandle(IntPtr handle);
    }
}
