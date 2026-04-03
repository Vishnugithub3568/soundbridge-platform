import { motion } from 'framer-motion';

function Navbar({ currentView, onViewChange, theme, onToggleTheme, jobCount }) {
  const isDarkMode = theme === 'dark';

  return (
    <motion.nav
      className="sticky top-0 z-40 mb-6 rounded-[28px] border border-white/10 bg-slate-950/70 px-4 py-3 backdrop-blur-xl md:px-6"
      initial={{ opacity: 0, y: -16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35 }}
    >
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <button
          type="button"
          onClick={() => onViewChange('dashboard')}
          className="flex items-center gap-3 text-left"
        >
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-400 via-sky-500 to-indigo-600 shadow-[0_0_28px_rgba(56,189,248,0.35)]">
            <span className="text-lg font-black text-white">S</span>
          </div>
          <div>
            <p className="bg-gradient-to-r from-cyan-300 via-sky-200 to-fuchsia-300 bg-clip-text text-xl font-black tracking-tight text-transparent">
              SoundBridge
            </p>
            <p className="text-xs text-slate-400">Modern playlist migration dashboard</p>
          </div>
        </button>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => onViewChange('dashboard')}
            className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
              currentView === 'dashboard'
                ? 'bg-white/12 text-white shadow-[0_0_24px_rgba(56,189,248,0.22)]'
                : 'text-slate-300 hover:bg-white/8 hover:text-white'
            }`}
          >
            Dashboard
          </button>
          <button
            type="button"
            onClick={() => onViewChange('history')}
            className={`rounded-full px-4 py-2 text-sm font-semibold transition ${
              currentView === 'history'
                ? 'bg-white/12 text-white shadow-[0_0_24px_rgba(168,85,247,0.22)]'
                : 'text-slate-300 hover:bg-white/8 hover:text-white'
            }`}
          >
            Job History
          </button>
          <div className="hidden h-8 w-px bg-white/10 md:block" />
          <span className="rounded-full border border-white/10 bg-white/5 px-3 py-2 text-xs font-semibold text-slate-300">
            {jobCount} saved jobs
          </span>
          <button
            type="button"
            onClick={onToggleTheme}
            role="switch"
            aria-checked={isDarkMode}
            aria-label="Toggle dark mode"
            className={`relative flex h-9 w-20 items-center rounded-full border px-1.5 transition duration-300 ${
              isDarkMode
                ? 'border-cyan-300/45 bg-gradient-to-r from-cyan-500 via-sky-500 to-blue-600 shadow-[0_0_34px_rgba(56,189,248,0.55)]'
                : 'border-cyan-300/25 bg-slate-900/70 shadow-[0_0_20px_rgba(56,189,248,0.25)]'
            }`}
          >
            <span className="absolute left-2 text-[9px] font-black tracking-[0.12em] text-white/90">ON</span>
            <span className="absolute right-2 text-[9px] font-black tracking-[0.12em] text-white/70">OFF</span>
            <span
              className={`absolute inline-flex h-6 w-6 items-center justify-center rounded-full bg-white text-[9px] font-black text-sky-700 shadow-[0_2px_10px_rgba(2,6,23,0.35)] transition-all duration-300 ${
                isDarkMode ? 'translate-x-[44px]' : 'translate-x-0'
              }`}
            >
              {isDarkMode ? '●' : '○'}
            </span>
          </button>
        </div>
      </div>
    </motion.nav>
  );
}

export default Navbar;