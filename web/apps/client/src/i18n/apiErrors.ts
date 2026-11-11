const API_ERRORS: Readonly<Record<string, string>> = {
  INVALID_CREDENTIALS: "El correo electrónico o la contraseña no son correctos",
  UNAUTHENTICATED: "Inicia sesión para continuar",
  FORBIDDEN: "No tienes permiso para realizar esta operación",
  RATE_LIMITED: "Se han enviado demasiadas solicitudes. Vuelve a intentarlo más tarde",
  NOT_FOUND: "No se encontró el recurso solicitado",
  ASSISTANT_NOT_FOUND: "No se encontró el asistente",
  SESSION_NOT_FOUND: "No se encontró la conversación",
  FILE_NOT_FOUND: "No se encontró el archivo",
  MEMORY_FACT_NOT_FOUND: "No se encontró el recuerdo",
  CHAT_REQUEST_FAILED: "No se pudo completar la solicitud de chat",
  TOOL_EXECUTION_FAILED: "No se pudo ejecutar la herramienta",
  INVALID_REQUEST: "La solicitud no es válida. Revisa los datos introducidos",
  INVALID_AGENT_TASK: "La tarea del agente no es válida",
  INVALID_ASSISTANT: "La configuración del asistente no es válida",
  INVALID_CHECKOUT: "No se pudo iniciar el pago. Revisa el plan seleccionado",
  INVALID_CREDIT_GRANT: "No se pudo asignar la cuota. Revisa el usuario y la cantidad",
  INVALID_DOCUMENT: "El documento no es válido",
  INVALID_FACT: "El recuerdo no es válido",
  INVALID_FILE_COMPLETION: "No se pudo confirmar la carga del archivo",
  INVALID_JOB: "La tarea no es válida",
  INVALID_MCP_SERVER: "La configuración del servidor MCP no es válida",
  INVALID_MEMORY_QUERY: "La búsqueda de recuerdos no es válida",
  INVALID_MEMORY_SPACE: "El espacio de memoria no es válido",
  INVALID_PAYMENT_WEBHOOK: "La notificación del pago no es válida",
  INVALID_PLAN: "El plan no es válido",
  INVALID_PRICE: "El precio no es válido",
  INVALID_PROVIDER: "La configuración del proveedor no es válida. Revisa la URL, el modelo y la clave",
  INVALID_PUSH_SUBSCRIPTION: "La suscripción a notificaciones no es válida",
  INVALID_SESSION: "La conversación no es válida",
  INVALID_UPLOAD: "No se pudo preparar la carga del archivo",
};

export function apiErrorMessage(body: unknown, fallback: string): string {
  if (body === null || typeof body !== "object") return fallback;
  if ("code" in body && typeof body.code === "string") {
    const translated = API_ERRORS[body.code];
    if (typeof translated === "string") return translated;
  }
  return "message" in body && typeof body.message === "string" ? body.message : fallback;
}
