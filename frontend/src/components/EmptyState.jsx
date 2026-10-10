import Icon from './Icon';

export default function EmptyState({ title = 'Nothing here yet', message, action }) {
  return (
    <div className="state-block">
      <span className="state-icon" aria-hidden="true">
        <Icon name="inbox" size={26} />
      </span>
      <h3>{title}</h3>
      {message && <p>{message}</p>}
      {action}
    </div>
  );
}
