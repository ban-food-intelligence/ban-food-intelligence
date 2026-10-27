import React, { useState } from 'react';
import RecallWarRoom from './components/RecallWarRoom';
import LotGenealogy from './components/LotGenealogy';
import ButcheryCostTable from './components/ButcheryCostTable';
import MLPredictionsTable from './components/MLPredictionsTable';

export default function App() {
  const [activeTab, setActiveTab] = useState('butchery');

  return (
    <div className="dashboard-container">
      <header className="header-banner">
        <div className="title-tagline">
          <h1>BAN Food Intelligence &bull; AVATAR ERP</h1>
          <p>Fason üretim maliyeti (EKP), kuzu parçalama mutabakatı, lot izlenebilirliği ve yapay zeka tahminleri: canlı Go API + MySQL verisi</p>
        </div>
        <div className="badge-team">
          Ali (MySQL/ML) &bull; Berke (Go/React) &bull; Nuri (Kotlin POS/ECDSA/Stacking)
        </div>
      </header>

      {/* Navigation Tabs */}
      <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem', borderBottom: '1px solid #232d42', paddingBottom: '0.5rem', flexWrap: 'wrap' }}>
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
            fontSize: '0.9rem',
          }}
        >
          Görev 1 & 2: Üretim Maliyeti ve Kuzu Parçalama
        </button>
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
            fontSize: '0.9rem',
          }}
        >
          Görev 3: Geri Çağırma
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
            fontSize: '0.9rem',
          }}
        >
          Görev 3: İleri ve Geri Lot İzlenebilirliği
        </button>
        <button
          onClick={() => setActiveTab('ml')}
          style={{
            background: activeTab === 'ml' ? 'rgba(139, 92, 246, 0.2)' : 'transparent',
            color: activeTab === 'ml' ? '#c4b5fd' : '#94a3b8',
            border: activeTab === 'ml' ? '1px solid #8b5cf6' : '1px solid transparent',
            borderRadius: '6px',
            padding: '0.6rem 1.2rem',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '0.9rem',
          }}
        >
          Görev 4A & 4B: Yapay Zeka Tahmin ve Sapma Raporu
        </button>
      </div>

      <main>
        {activeTab === 'butchery' && <ButcheryCostTable />}
        {activeTab === 'recall' && <RecallWarRoom />}
        {activeTab === 'genealogy' && <LotGenealogy />}
        {activeTab === 'ml' && <MLPredictionsTable />}
      </main>
    </div>
  );
}
