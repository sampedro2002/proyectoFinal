export default function ConfirmModal({ isOpen, title, message, onConfirm, onCancel }) {
  if (!isOpen) return null;

  const handleKeyDown = (e) => {
    if (e.key === 'Escape' && onCancel) onCancel();
  };

  return (
    <div
      className="modal-overlay"
      onKeyDown={handleKeyDown}
      tabIndex={-1}
      style={{
        position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
        background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center',
        justifyContent: 'center', zIndex: 9999
      }}
    >
      <div className="card" style={{ width: 400, maxWidth: '90%', background: 'var(--panel, #1e293b)' }}>
        <h3 style={{ marginTop: 0 }}>{title}</h3>
        <p style={{ color: '#94a3b8' }}>{message}</p>
        <div className="row" style={{ marginTop: 24, justifyContent: 'flex-end' }}>
          <button type="button" className="ghost" onClick={onCancel}>
            Cancelar
          </button>
          <button type="button" className="danger" onClick={onConfirm}>
            Confirmar
          </button>
        </div>
      </div>
    </div>
  );
}
