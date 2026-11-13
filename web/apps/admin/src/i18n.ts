export const ADMIN_MESSAGES = {
  "brand": {
    "zh": "MUSE CONTROL PLANE",
    "es": "ADMINISTRACIÓN DE MUSE"
  },
  "title": {
    "zh": "运营后台",
    "es": "Panel de administración"
  },
  "accessHint": {
    "zh": "仅平台管理员可进入。首个管理员由部署时的 bootstrap 环境变量创建。",
    "es": "Solo pueden acceder los administradores de la plataforma. La primera cuenta se crea con las variables de entorno de inicialización al desplegar el servicio."
  },
  "email": {
    "zh": "管理员邮箱",
    "es": "Correo del administrador"
  },
  "password": {
    "zh": "管理员密码",
    "es": "Contraseña del administrador"
  },
  "login": {
    "zh": "登录",
    "es": "Iniciar sesión"
  },
  "loginFailed": {
    "zh": "登录失败",
    "es": "No se pudo iniciar sesión"
  },
  "loadFailed": {
    "zh": "加载失败",
    "es": "No se pudieron cargar los datos"
  },
  "priceFailed": {
    "zh": "调价失败",
    "es": "No se pudo actualizar el precio"
  },
  "grantFailed": {
    "zh": "发放失败",
    "es": "No se pudieron asignar los créditos"
  },
  "planFailed": {
    "zh": "创建套餐失败",
    "es": "No se pudo crear el plan"
  },
  "logout": {
    "zh": "退出",
    "es": "Cerrar sesión"
  },
  "users": {
    "zh": "用户",
    "es": "Usuarios"
  },
  "activePlans": {
    "zh": "启用套餐",
    "es": "Planes activos"
  },
  "auditCount": {
    "zh": "审计记录",
    "es": "Registros de auditoría"
  },
  "plansPrices": {
    "zh": "套餐与调价",
    "es": "Planes y precios"
  },
  "noPrice": {
    "zh": "无价格",
    "es": "Sin precio"
  },
  "changePrice": {
    "zh": "调价",
    "es": "Cambiar precio"
  },
  "createPlan": {
    "zh": "创建套餐",
    "es": "Crear plan"
  },
  "planId": {
    "zh": "套餐 ID",
    "es": "ID del plan"
  },
  "name": {
    "zh": "名称",
    "es": "Nombre"
  },
  "monthlyFen": {
    "zh": "月价（分）",
    "es": "Precio mensual (centavos de yuan)"
  },
  "annualFen": {
    "zh": "年价（分）",
    "es": "Precio anual (centavos de yuan)"
  },
  "includedCredits": {
    "zh": "包含额度",
    "es": "Créditos incluidos"
  },
  "byok": {
    "zh": "BYOK",
    "es": "Usar clave propia (BYOK)"
  },
  "hosted": {
    "zh": "Hosted",
    "es": "Usar modelos de la plataforma"
  },
  "create": {
    "zh": "创建",
    "es": "Crear"
  },
  "grantTitle": {
    "zh": "额度发放",
    "es": "Asignación de créditos"
  },
  "userId": {
    "zh": "用户 ID",
    "es": "ID del usuario"
  },
  "credits": {
    "zh": "额度",
    "es": "Créditos"
  },
  "grant": {
    "zh": "发放",
    "es": "Asignar"
  },
  "recentAudit": {
    "zh": "最近审计",
    "es": "Auditoría reciente"
  },
  "editPlan": {
    "zh": "调整 {name}",
    "es": "Cambiar precio de {name}"
  },
  "cancel": {
    "zh": "取消",
    "es": "Cancelar"
  },
  "publishPrice": {
    "zh": "发布价格版本",
    "es": "Publicar precio"
  },
  "requestFailed": {
    "zh": "请求失败 ({status})",
    "es": "La solicitud falló ({status})"
  },
  "creditsCount": {
    "zh": "{count} credits",
    "es": "{count} créditos"
  },
  "monthlyPrice": {
    "zh": "{price}/月",
    "es": "{price}/mes"
  },
  "pageTitle": {
    "zh": "Muse Admin",
    "es": "Administración de Muse"
  }
} as const;

export type AdminMessage = keyof typeof ADMIN_MESSAGES;
export type AdminLocale = "zh" | "es";

export function adminLocale(languages: readonly string[] = typeof navigator === "undefined" ? [] : navigator.languages): AdminLocale {
  for (const language of languages) {
    const code = language.toLowerCase().split(/[-_]/)[0];
    if (code === "es" || code === "zh") return code;
  }
  return "zh";
}

export function t(key: AdminMessage, params: Readonly<Record<string, string | number>> = {}, languages?: readonly string[]): string {
  return ADMIN_MESSAGES[key][adminLocale(languages)].replace(/\{(\w+)\}/g, (slot, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : slot,
  );
}

export function monthlyPrice(fen: number, languages?: readonly string[]): string {
  const price = new Intl.NumberFormat(adminLocale(languages), { style: "currency", currency: "CNY" }).format(fen / 100);
  return t("monthlyPrice", { price }, languages);
}
