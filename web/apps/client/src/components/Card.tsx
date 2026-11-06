import type { ReactNode } from "react";
import { SectionLabel } from "./SectionLabel.js";
import "./primitives.css";

export interface CardProps {
  readonly children?: ReactNode;
  /** Relleno:true Usado cuando cardInner(16/12),false Deje que las filas internas se organicen solas (para agrupar listas). */
  readonly padded?: boolean;
  readonly className?: string;
}

/**
 * Tarjetas con esquinas redondeadas:largeCard esquinas redondeadas(24dp) + Color de fondo de la tarjeta + micro elevación.
 *
 * Corresponde a Compose CardGroup MuseSurface(shape = MuseShapes.largeCard,
 * color = colorScheme.surface, elevation = MuseElevation.micro).
 */
export function Card({ children, padded = false, className }: CardProps) {
  const classes = ["muse-card"];
  if (padded) classes.push("is-padded");
  if (className !== undefined) classes.push(className);
  return <div className={classes.join(" ")}>{children}</div>;
}

export interface CardGroupProps {
  /** Título del grupo (representado como Etiqueta de sección encima de la tarjeta). */
  readonly title?: ReactNode;
  readonly children?: ReactNode;
  readonly className?: string;
}

/**
 * Contenedor de grupo: título de grupo opcional + una tarjeta con esquinas redondeadas compartidas.
 * Utilice ListRow para dibujar líneas separadas para las filas de la tarjeta (la última fila se pasa divider={false}).
 */
export function CardGroup({ title, children, className }: CardGroupProps) {
  const classes = ["muse-card-group"];
  if (className !== undefined) classes.push(className);
  return (
    <section className={classes.join(" ")}>
      {title !== undefined && <SectionLabel>{title}</SectionLabel>}
      <Card>{children}</Card>
    </section>
  );
}
