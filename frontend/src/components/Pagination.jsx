import Icon from './Icon';

/**
 * Pagination — the previous/next footer shared by every paged list.
 *
 * Each console screen had copied the same four lines: a count, "page X of Y" and
 * two buttons. They had drifted in wording and markup. This is that footer once,
 * driven straight from the server's page object, so paging feels identical
 * everywhere and reads the same to a screen reader.
 *
 * `page` is the server page (Spring or the app's DTO): it may expose the index
 * as `page` or `number`, and carries `first`, `last`, `totalPages`,
 * `totalElements`.
 */
export default function Pagination({ page, onChange, noun = 'item', nounPlural }) {
  if (!page) return null;

  const index = page.page ?? page.number ?? 0;
  const total = Math.max(page.totalPages ?? 1, 1);
  const plural = nounPlural || `${noun}s`;
  const totalElements = page.totalElements;

  return (
    <nav className="pagination" aria-label="Pagination">
      <span className="pagination-summary">
        {totalElements != null && (
          <>
            {totalElements} {totalElements === 1 ? noun : plural} ·{' '}
          </>
        )}
        Page {index + 1} of {total}
      </span>
      <div className="pagination-controls">
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          disabled={page.first}
          onClick={() => onChange((p) => Math.max(p - 1, 0))}
        >
          <Icon name="chevron-left" size={16} />
          Previous
        </button>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          disabled={page.last}
          onClick={() => onChange((p) => p + 1)}
        >
          Next
          <Icon name="chevron-right" size={16} />
        </button>
      </div>
    </nav>
  );
}
