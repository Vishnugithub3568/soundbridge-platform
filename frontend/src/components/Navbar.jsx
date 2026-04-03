import { motion } from 'framer-motion';

function Navbar({ currentView, onViewChange, theme, onToggleTheme, jobCount }) {
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
            className="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm font-semibold text-white transition hover:scale-[1.02] hover:bg-white/10 hover:shadow-[0_0_24px_rgba(56,189,248,0.2)]"
          >
            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </button>
        </div>
      </div>
    </motion.nav>
  );
}

export default Navbar;