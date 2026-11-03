import type { ReactNode } from "react";
import "./primitives.css";

export interface SectionLabelProps {
  readonly children: ReactNode;
  readonly className?: string;
}

/**
 * Subtítulos grupales. Correspondiente a componer SectionLabel —— labelLarge semigrueso,onSurface @80%,
 * Trae el tuyo bottom = tightGap(4dp) El espaciado inferior (el espaciado superior lo proporciona el espaciado del grupo del usuario).
 */
export function SectionLabel({ children, className }: SectionLabelProps) {
  const classes = ["muse-section-label"];
  if (className !== undefined) classes.push(className);
  return <p className={classes.join(" ")}>{children}</p>;
}
