import type { SVGProps } from "react";

type Direction = "right" | "up" | "up-right";

const paths: Record<Direction, string> = {
  right: "M4 12h16m-6-6 6 6-6 6",
  up: "M12 20V4m-6 6 6-6 6 6",
  "up-right": "M5 19 19 5M8 5h11v11",
};

/** Small shared vector used for navigational actions, not a general icon library. */
export function ArrowIcon({ direction, ...props }: SVGProps<SVGSVGElement> & { direction: Direction }) {
  return (
    <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>
      <path d={paths[direction]} />
    </svg>
  );
}
