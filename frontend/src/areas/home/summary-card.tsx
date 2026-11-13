import { Link } from "../../router.tsx";
import { Icon } from "../../ui/icon.tsx";
import { hrefFor, find } from "../../manifest.ts";

/** 목업과 로그인 홈이 공유하는 나의 하루 카드. 값과 링크만 데이터 출처에 따라 달라진다. */
export function SummaryCard({ kind, label, value, sub, unit, linkText }: {
  kind: string;
  label: string;
  value: string;
  sub: string;
  unit: string;
  linkText: string;
}) {
  return (
    <article className={`summary-card summary-${kind}`}>
      <div className="summary-card-heading">
        <h3>{label}</h3>
        <span className="summary-date">{sub}</span>
      </div>
      <div className="summary-card-content">
        <p className="summary-value">
          <span className="summary-number">{value}</span>
          <span className="summary-unit">{unit}</span>
        </p>
        <Link className="summary-link" href={hrefFor(find("01_pop_todo"), { "todo-view": kind })}>
          <span>{linkText}</span>
          <span className="summary-chevron" aria-hidden="true"><Icon name="chev" /></span>
        </Link>
      </div>
    </article>
  );
}
