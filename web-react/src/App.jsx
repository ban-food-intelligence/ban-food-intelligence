import React, { useEffect, useState } from 'react';
import { LangProvider, useLang } from './lib/i18n';
import Overview from './views/Overview';
import { Movements, Purchases, Sales } from './views/Reports';
import Production from './views/Production';
import Butchery from './views/Butchery';
import TraceRecall from './views/TraceRecall';
import AI from './views/AI';

const TABS = [
  { id: 'overview', view: Overview },
  { id: 'purchases', view: Purchases },
  { id: 'production', view: Production },
  { id: 'butchery', view: Butchery },
  { id: 'movements', view: Movements },
  { id: 'sales', view: Sales },
  { id: 'trace', view: TraceRecall },
  { id: 'ai', view: AI },
];

function readTab() {
  const h = window.location.hash.replace('#', '');
  return TABS.some((t) => t.id === h) ? h : 'overview';
}

function Shell() {
  const { t, lang, setLang } = useLang();
  const [tab, setTab] = useState(readTab);
  useEffect(() => {
    const onHash = () => setTab(readTab());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  const open = (id) => {
    window.location.hash = id;
    setTab(id);
  };
  const View = TABS.find((x) => x.id === tab).view;

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-name">{t('appName')}</div>
          <div className="brand-sub">{t('appSub')}</div>
        </div>
        <nav className="nav" aria-label="Sections">
          {TABS.map((x) => (
            <button key={x.id} className={x.id === tab ? 'active' : ''} aria-current={x.id === tab ? 'page' : undefined} onClick={() => open(x.id)}>
              {t(`tab_${x.id}`)}
            </button>
          ))}
        </nav>
        <div className="lang" role="group" aria-label="Language">
          {['tr', 'en'].map((l) => (
            <button key={l} className={l === lang ? 'active' : ''} onClick={() => setLang(l)}>{l.toUpperCase()}</button>
          ))}
        </div>
      </aside>
      <main className="main">
        <View onOpen={open} />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <LangProvider>
      <Shell />
    </LangProvider>
  );
}
