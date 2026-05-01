/* eslint-disable */
// Schedules — list + create flow. Template-first approach per brief.

const scheduleTypeLabel = {
  ONE_OFF: 'One-off',
  RECURRING_DAILY: 'Daily',
  RECURRING_WEEKLY: 'Weekly',
};

const ScheduleRow = ({ sc, onOpen }) => {
  const statusTone = sc.status === 'active' ? 'ok' : sc.status === 'scheduled' ? 'info' : 'outline';
  return (
    <button onClick={onOpen} style={{
      display: 'grid', gridTemplateColumns: '18px 1.6fr 1fr 1.2fr 80px 60px',
      gap: 16, alignItems: 'center',
      width: '100%', padding: '12px 20px',
      borderBottom: 'var(--border-faint)',
      textAlign: 'left',
    }}>
      <StatusDot status={sc.status === 'active' ? 'online' : sc.status === 'paused' ? 'offline' : 'updating'} size={7} />
      <div>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{sc.name}</div>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>{sc.brand}</div>
      </div>
      <div style={{ fontSize: 12, color: 'var(--ink-2)' }}>{sc.range}</div>
      <div><Chip tone="outline">{scheduleTypeLabel[sc.type]}</Chip></div>
      <div className="tnum" style={{ fontSize: 12, color: 'var(--ink-2)' }}>{sc.screens} screens</div>
      <div><Chip tone={statusTone}>{sc.status}</Chip></div>
    </button>
  );
};

const TemplateCard = ({ icon, title, desc, active, onClick }) => (
  <button onClick={onClick} style={{
    flex: 1, padding: 16,
    border: active ? '1.5px solid var(--ink-1)' : 'var(--border-strong)',
    borderRadius: 10,
    background: 'var(--ink-10)',
    textAlign: 'left',
    display: 'flex', flexDirection: 'column', gap: 10,
  }}>
    <div style={{ width: 32, height: 32, borderRadius: 8, background: 'var(--ink-9)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--ink-1)' }}>
      {icon}
    </div>
    <div>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 3 }}>{title}</div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', lineHeight: 1.45 }}>{desc}</div>
    </div>
  </button>
);

const Schedules = ({ initialMode = 'list' }) => {
  const [mode, setMode] = React.useState(initialMode); // 'list' | 'create'
  const [template, setTemplate] = React.useState('seasonal');

  return (
    <AppShell current="schedules">
      <PageHeader
        title={mode === 'list' ? 'Schedules' : 'New schedule'}
        subtitle={mode === 'list' ? '6 schedules · 3 active · 2 upcoming' : undefined}
        crumbs={mode === 'create' ? [{ label: 'Schedules', href: '/schedules' }, 'New schedule'] : undefined}
        actions={
          mode === 'list' ? (
            <>
              <Button variant="secondary" size="sm" icon={<Icon.filter size={12} />}>Status</Button>
              <Button variant="primary" size="sm" icon={<Icon.plus size={13} />} onClick={() => { setMode('create'); navigate('/schedules/new'); }}>New schedule</Button>
            </>
          ) : (
            <>
              <Button variant="ghost" size="sm" onClick={() => { setMode('list'); navigate('/schedules'); }}>Cancel</Button>
              <Button variant="primary" size="sm">Save schedule</Button>
            </>
          )
        }
      />
      {mode === 'list' ? (
        <div style={{ flex: 1, overflow: 'auto' }}>
          <div style={{ padding: '14px 20px 10px', display: 'flex', gap: 16, fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, borderBottom: 'var(--border)' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '18px 1.6fr 1fr 1.2fr 80px 60px', gap: 16, width: '100%', paddingLeft: 0 }}>
              <span />
              <span>Name</span>
              <span>When</span>
              <span>Type</span>
              <span>Targets</span>
              <span>Status</span>
            </div>
          </div>
          <div>
            {MOCK_SCHEDULES.map((sc) => <ScheduleRow key={sc.id} sc={sc} />)}
          </div>

          {/* Conflict panel */}
          <div style={{ margin: '20px 20px', border: 'var(--border)', borderRadius: 10, padding: 16, background: 'var(--ink-9)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <span style={{ color: 'var(--warn)' }}><Icon.warning size={14} /></span>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', flex: 1 }}>1 conflict to resolve</div>
              <Button variant="ghost" size="sm" iconRight={<Icon.chevR size={12} />}>Review</Button>
            </div>
            <div style={{ fontSize: 12, color: 'var(--ink-3)', paddingLeft: 24 }}>
              <span style={{ fontWeight: 500, color: 'var(--ink-1)' }}>Launch day — Razr 50</span> overlaps with <span style={{ fontWeight: 500, color: 'var(--ink-1)' }}>Morning coffee window</span> on 4 screens at Saks Fifth Avenue.
            </div>
          </div>
        </div>
      ) : (
        <div style={{ flex: 1, overflow: 'auto', padding: '28px 32px 40px', maxWidth: 780 }}>
          {/* Template picker */}
          <div style={{ marginBottom: 28 }}>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 }}>1 · What kind of schedule?</div>
            <div style={{ display: 'flex', gap: 10 }}>
              <TemplateCard icon={<Icon.star size={16} />} title="Seasonal takeover" desc="Switch content for a date range — holidays, launch windows, sales." active={template === 'seasonal'} onClick={() => setTemplate('seasonal')} />
              <TemplateCard icon={<Icon.schedule size={16} />} title="Time of day" desc="Different content for morning, afternoon, evening windows." active={template === 'daily'} onClick={() => setTemplate('daily')} />
              <TemplateCard icon={<Icon.globe size={16} />} title="Day of week" desc="Weekday vs weekend, or specific days only." active={template === 'weekly'} onClick={() => setTemplate('weekly')} />
            </div>
          </div>

          {/* Step 2 — details */}
          <div style={{ marginBottom: 28 }}>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 }}>2 · Details</div>
            <div style={{ border: 'var(--border)', borderRadius: 10, padding: 20, background: 'var(--ink-10)' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 16 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 5 }}>Name</label>
                  <Input value="Holiday window 2025" />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 5 }}>Priority</label>
                  <div style={{ display: 'flex', alignItems: 'center', height: 32, padding: '0 10px', border: 'var(--border-strong)', borderRadius: 6, fontSize: 13, color: 'var(--ink-2)' }}>
                    <span style={{ flex: 1 }}>High · overrides defaults</span>
                    <Icon.chevD size={12} />
                  </div>
                </div>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 5 }}>Starts</label>
                  <Input value="Dec 1, 2025 · 09:00 GMT" />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 5 }}>Ends</label>
                  <Input value="Jan 6, 2026 · 23:00 GMT" />
                </div>
              </div>
            </div>
          </div>

          {/* Step 3 — content + targets */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 }}>3 · Content & targets</div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <div style={{ border: 'var(--border)', borderRadius: 10, padding: 16 }}>
                <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
                  <div style={{ flex: 1, fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Content</div>
                  <Button variant="ghost" size="sm">Pick</Button>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {MOCK_VIDEOS.slice(0, 3).map((v) => (
                    <div key={v.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 8px', background: 'var(--ink-9)', borderRadius: 6 }}>
                      <div style={{ width: 36, height: 22, borderRadius: 3, overflow: 'hidden' }}>
                        <Thumbnail title={v.title} brand={v.brand} size="sm" />
                      </div>
                      <span style={{ fontSize: 12, color: 'var(--ink-2)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
                    </div>
                  ))}
                  <div style={{ fontSize: 11, color: 'var(--ink-4)', padding: '4px 8px' }}>+ 5 more</div>
                </div>
              </div>
              <div style={{ border: 'var(--border)', borderRadius: 10, padding: 16 }}>
                <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
                  <div style={{ flex: 1, fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Targets</div>
                  <Button variant="ghost" size="sm">Pick</Button>
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  <Chip>All UK/EU · 84 screens</Chip>
                  <Chip tone="outline">+ Saks Fifth Avenue</Chip>
                </div>
                <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 10 }}>84 screens across 6 stores in UK/EU</div>
              </div>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
};

Object.assign(window, { Schedules });
