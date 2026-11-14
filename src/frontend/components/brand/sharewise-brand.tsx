import type { SVGProps } from "react";

type Props = SVGProps<SVGSVGElement>;

/** Reusable compact symbol. Two parallel entries suggest a balanced ledger. */
export function ShareWiseMark(props: Props) {
  return (
    <svg viewBox="0 0 36 36" fill="none" aria-hidden="true" {...props}>
      <rect width="36" height="36" rx="3" fill="currentColor" />
      <path d="M8 11H28M8 18H21M8 25H28" stroke="var(--sw-paper)" strokeWidth="3" />
      <path d="M25 15L28 18L25 21" stroke="var(--sw-paper)" strokeWidth="2.5" strokeLinecap="square" />
    </svg>
  );
}
