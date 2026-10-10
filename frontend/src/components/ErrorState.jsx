import Icon from './Icon';

export default function ErrorState({ message = 'Something went wrong.', onRetry }) {
  return (
    <div className="state-block">
      <span className="state-icon state-icon-bad" aria-hidden="true">
        <Icon name="alert" size={26} />
      </span>
      <h3 className="text-danger">Unable to load this</h3>
      <p>{message}</p>
      {onRetry && <button type="button" className="btn btn-secondary" onClick={onRetry}>Retry</button>}
    </div>
  );
}
