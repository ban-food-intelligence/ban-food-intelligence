import React, { useState } from 'react';
import RecallWarRoom from './components/RecallWarRoom';
import LotGenealogy from './components/LotGenealogy';
import ButcheryCostTable from './components/ButcheryCostTable';

export default function App() {
  const [activeTab, setActiveTab] = useState('recall');

  return (
    <div className="dashboard-container">
      <header className="header-banner">
        <div className="title-tagline">
          <h1>BAN Food Intelligence &bull; AVATAR ERP</h1>
          <p>Real-Time Costing, Recursive Batch Traceability, and Emergency Recall Management</p>
        </div>
        <div className="badge-team">
          Ali (DB/Model) &bull; Berke (Go/React/Model) &bull; Nuri (Kotlin/Sync/Security)
        </div>
      </header>

      {/* Navigation Tabs */}
      <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem', borderBottom: '1px solid #232d42', paddingBottom: '0.5rem' }}>
        <button
          onClick={() => setActiveTab('recall')}
          style={{
            background: activeTab === 'recall' ? 'rgba(239, 68, 68, 0.2)' : 'transparent',
            color: activeTab === 'recall' ? '#f87171' : '#94a3b8',
            border: activeTab === 'recall' ? '1px solid #ef4444' : '1px solid transparent',
            borderRadius: '6px',
            padding: '0.6rem 1.2rem',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '0.9rem'
          }}
        >
          🚨 Recall War Room
        </button>
        <button
          onClick={() => setActiveTab('genealogy')}
          style={{
            background: activeTab === 'genealogy' ? 'rgba(6, 182, 212, 0.2)' : 'transparent',
            color: activeTab === 'genealogy' ? '#38bdf8' : '#94a3b8',
            border: activeTab === 'genealogy' ? '1px solid #06b6d4' : '1px solid transparent',
            borderRadius: '6px',
            padding: '0.6rem 1.2rem',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '0.9rem'
          }}
        >
          🌿 Lot Genealogy Tree
        </button>
        <button
          onClick={() => setActiveTab('butchery')}
          style={{
            background: activeTab === 'butchery' ? 'rgba(16, 185, 129, 0.2)' : 'transparent',
            color: activeTab === 'butchery' ? '#34d399' : '#94a3b8',
            border: activeTab === 'butchery' ? '1px solid #10b981' : '1px solid transparent',
            borderRadius: '6px',
            padding: '0.6rem 1.2rem',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '0.9rem'
          }}
        >
          🥩 Task 2 Butchery Cost Tables
        </button>
      </div>

      <main>
        {activeTab === 'recall' && <RecallWarRoom />}
        {activeTab === 'genealogy' && <LotGenealogy />}
        {activeTab === 'butchery' && <ButcheryCostTable />}
      </main>
    </div>
  );
}
