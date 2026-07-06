import { useState, useRef, useEffect, useMemo } from 'react';

export default function EmployeeSelect({ employees, value, onChange }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const ref = useRef(null);

  useEffect(() => {
    function handleClickOutside(event) {
      if (ref.current && !ref.current.contains(event.target)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const selectedEmp = useMemo(() => employees.find(e => e.id === String(value) || e.id === Number(value)), [employees, value]);
  const displayValue = selectedEmp ? `${selectedEmp.identityCard} - ${selectedEmp.fullName}` : '';

  const filtered = useMemo(() => {
    if (!search) return employees;
    const term = search.toLowerCase();
    return employees.filter(e => 
      (e.fullName && e.fullName.toLowerCase().includes(term)) || 
      (e.identityCard && e.identityCard.includes(term))
    );
  }, [search, employees]);

  return (
    <div className="searchable-select" ref={ref} style={{ position: 'relative', width: '100%', minWidth: '220px' }}>
      <input
        type="text"
        placeholder="Todos (Buscar...)"
        value={open ? search : (value ? displayValue : '')}
        onClick={() => setOpen(true)}
        onChange={(e) => {
          setSearch(e.target.value);
          setOpen(true);
        }}
        style={{ width: '100%', boxSizing: 'border-box' }}
      />
      
      {open && (
        <div style={{
          position: 'absolute',
          top: '100%',
          left: 0,
          right: 0,
          maxHeight: '240px',
          overflowY: 'auto',
          background: 'var(--panel)',
          border: '1px solid var(--border)',
          borderRadius: '8px',
          marginTop: '4px',
          zIndex: 9999,
          boxShadow: '0 4px 12px rgba(0,0,0,0.5)'
        }}>
          <div
            style={{ padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid var(--border)' }}
            onClick={() => { onChange(''); setOpen(false); setSearch(''); }}
          >
            Todos
          </div>
          {filtered.map(emp => (
            <div
              key={emp.id}
              style={{ padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid var(--border)', display: 'flex', flexDirection: 'column' }}
              onClick={() => { onChange(emp.id); setOpen(false); setSearch(''); }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--panel-2)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
            >
              <span>{emp.fullName}</span>
              <span style={{ fontSize: '12px', color: 'var(--muted)' }}>{emp.identityCard}</span>
            </div>
          ))}
          {filtered.length === 0 && (
            <div style={{ padding: '10px 12px', color: 'var(--muted)', textAlign: 'center' }}>
              No hay resultados
            </div>
          )}
        </div>
      )}
    </div>
  );
}
