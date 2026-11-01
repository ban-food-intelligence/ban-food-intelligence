import React, { useEffect, useState } from 'react';
import { LangProvider, useLang } from './lib/i18n';
import Overview from './views/Overview';
import { Movements, Purchases, Sales, Shipments } from './views/Reports';
import Production from './views/Production';
import Butchery from './views/Butchery';
import TraceRecall from './views/TraceRecall';
import AI from './views/AI';
import Lots from './views/Lots';
import MasterData from './views/MasterData';
import Alerts, { AlertBell } from './views/Alerts';
import { LotPanelProvider } from './views/LotPanel';

const TABS = [
  { id: 'overview', view: Overview },
  { id: 'alerts', view: Alerts },
  { id: 'lots', view: Lots },
  { id: 'purchases', view: Purchases },
  { id: 'production', view: Production },
  { id: 'butchery', view: Butchery },
  { id: 'movements', view: Movements },
  { id: 'shipments', view: Shipments },
  { id: 'sales', view: Sales },
  { id: 'trace', view: TraceRecall },
  { id: 'ai', view: AI },
  { id: 'master', view: MasterData },
];

const NAV_GROUPS = [
  { key: 'general', tabs: ['overview', 'alerts', 'master'] },
  { key: 'task1', tabs: ['purchases', 'production'] },
  { key: 'task2', tabs: ['butchery', 'sales'] },
  { key: 'task3', tabs: ['lots', 'movements', 'shipments', 'trace'] },
  { key: 'task4', tabs: ['ai'] },
];

// #trace or #trace?lot=SUC-2026-045: the tab, then optional parameters for it.
function readRoute() {
  const route = window.location.hash.replace('#', '');
  const tab = route.split('?')[0];
  return TABS.some((t) => t.id === tab) ? { tab, route } : { tab: 'overview', route: 'overview' };
}

function Shell() {
  const { t, lang, setLang } = useLang();
  const [{ tab, route }, setRoute] = useState(readRoute);
  useEffect(() => {
    const onHash = () => setRoute(readRoute());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  const open = (id) => {
    window.location.hash = id;
    setRoute(readRoute());
  };
  const View = TABS.find((x) => x.id === tab).view;

  return (
    <LotPanelProvider onOpenTrace={(lot) => open(`trace?lot=${encodeURIComponent(lot)}`)}>
      <div className="app">
        <aside className="sidebar">
          <div className="brand">
            <div className="brand-name">{t('appName')}</div>
            <div className="brand-sub">{t('appSub')}</div>
          </div>
          <AlertBell onOpen={open} />
          <nav className="nav" aria-label="Sections">
            {NAV_GROUPS.map((group) => (
              <div className="nav-group" key={group.key} role="group" aria-label={t(`nav_${group.key}`)}>
                <div className="nav-heading">{t(`nav_${group.key}`)}</div>
                {group.tabs.map((id) => (
                  <button key={id} className={id === tab ? 'active' : ''} aria-current={id === tab ? 'page' : undefined} onClick={() => open(id)}>
                    {t(`tab_${id}`)}
                  </button>
                ))}
              </div>
            ))}
          </nav>
          <div className="lang" role="group" aria-label="Language">
            {['tr', 'en'].map((l) => (
              <button key={l} className={l === lang ? 'active' : ''} onClick={() => setLang(l)}>{l.toUpperCase()}</button>
            ))}
          </div>
        </aside>
        <main className="main">
          <View key={route} onOpen={open} />
        </main>
      </div>
    </LotPanelProvider>
  );
}

export default function App() {
  return (
    <LangProvider>
      <Shell />
    </LangProvider>
  );
}
