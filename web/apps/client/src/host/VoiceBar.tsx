import { useEffect, useRef, useState } from "react";
import { PauseIcon, PlayIcon, VoiceIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { formatVoiceDuration } from "./format.js";

interface VoiceBarProps {
  /** dirección reproducible;undefined el tiempo sólo se puede degradar a una barra de tiempo estática. */
  readonly src?: string | undefined;
  /** Host La duración de la entrega (milisegundos); si no, léelo de los metadatos de audio. */
  readonly durationMs?: number | undefined;
}

/**
 * Barra de voz.
 *
 * - Hay una dirección accesible: play/Pausa + progreso + duración, la capa inferior es una `<audio preload="metadata">`;
 * - Sólo la duración (la antigua Hostia dio `file://` Cita): degradado a "voz · 12″"Barra estática;
 * - Si la carga/reproducción de audio falla, la barra estática volverá y se marcará como "no se puede reproducir" sin dejar una barra de control silenciada.
 */
export function VoiceBar({ src, durationMs }: VoiceBarProps) {
  const { t } = useI18n();
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [measuredMs, setMeasuredMs] = useState<number | undefined>(undefined);
  const [failed, setFailed] = useState(false);

  // Cuando el mismo nodo de mensaje se multiplexa con otra voz, el estado de reproducción se restablece a cero.
  useEffect(() => {
    setPlaying(false);
    setProgress(0);
    setMeasuredMs(undefined);
    setFailed(false);
  }, [src]);

  const totalMs = durationMs !== undefined && durationMs > 0 ? durationMs : measuredMs;
  const duration = formatVoiceDuration(totalMs);
  const label = duration.length > 0 ? duration : t("voice.unknown");
  const aria = duration.length > 0 ? t("voice.aria", { duration }) : t("voice.unknown");

  if (src === undefined || failed) {
    return (
      <div className="host-voice is-static" role="group" aria-label={aria}>
        <span className="host-voice-icon">
          <VoiceIcon />
        </span>
        <span className="host-voice-time">{duration.length > 0 ? t("voice.static", { duration }) : t("voice.unknown")}</span>
        {failed && <span className="host-voice-note">{t("voice.cannotPlay")}</span>}
      </div>
    );
  }

  const toggle = () => {
    const audio = audioRef.current;
    if (audio === null) return;
    if (playing) {
      audio.pause();
      return;
    }
    void audio.play().catch(() => setFailed(true));
  };

  return (
    <div className="host-voice" role="group" aria-label={aria}>
      <button
        className="quiet host-voice-button"
        type="button"
        aria-label={playing ? t("voice.pause") : t("voice.play")}
        onClick={toggle}
      >
        {playing ? <PauseIcon /> : <PlayIcon />}
      </button>
      <span className="host-voice-track" aria-hidden="true">
        <span className="host-voice-fill" style={{ width: `${Math.round(Math.min(1, progress) * 100)}%` }} />
      </span>
      <span className="host-voice-time">
        {playing ? formatVoiceDuration(Math.max(1, (totalMs ?? 0) * progress)) || label : label}
      </span>
      <audio
        ref={audioRef}
        className="host-voice-audio"
        src={src}
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          setPlaying(false);
          setProgress(0);
        }}
        onTimeUpdate={(event) => {
          const audio = event.currentTarget;
          if (Number.isFinite(audio.duration) && audio.duration > 0) setProgress(audio.currentTime / audio.duration);
        }}
        onLoadedMetadata={(event) => {
          const seconds = event.currentTarget.duration;
          if (Number.isFinite(seconds) && seconds > 0) setMeasuredMs(Math.round(seconds * 1_000));
        }}
        onError={() => setFailed(true)}
      />
    </div>
  );
}
