import "./primitives.css";

export interface SwitchProps {
  readonly checked: boolean;
  readonly onChange: (next: boolean) => void;
  /** Nombre de accesibilidad; omitirlo requiere que el usuario proporcione una etiqueta de texto externa. */
  readonly label?: string;
  readonly disabled?: boolean;
}

/**
 * cambiar. Geometría y alineación de apariencia MuseSwitch: Pistas 51×31, pulgar 27, retraído 2dp (área táctil 48dp),
 * Activar primario/onPrimary y apagar lerp(surfaceVariant, onSurface, 12%),
 * El desplazamiento del pulgar y el cambio de color de la pista se utilizan juntos. tactile(200ms) easeOutCubic.
 */
export function Switch({ checked, onChange, label, disabled = false }: SwitchProps) {
  const classes = ["muse-switch"];
  if (checked) classes.push("is-on");
  return (
    <button
      aria-checked={checked}
      aria-label={label}
      className={classes.join(" ")}
      disabled={disabled}
      role="switch"
      type="button"
      onClick={() => onChange(!checked)}
    >
      <span className="muse-switch-track">
        <span className="muse-switch-thumb" />
      </span>
    </button>
  );
}
