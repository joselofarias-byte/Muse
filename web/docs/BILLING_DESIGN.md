# Paquete de RMB y diseño de cuotas

## Objetos de dominio

`Plan`,`PriceVersion`,`Entitlement`,`Subscription`,`Invoice`,`Payment`,`CreditLedger`,`UsageRecord`,`Coupon`,`Refund`.

## principios

- La cantidad se expresa uniformemente en centavos y no se utilizan números de punto flotante.
- La versión de precio admite efectos futuros, seguimiento histórico y reversión.
- El ciclo de pago utiliza la instantánea del precio en el pedido y no cambia debido a ajustes de precio en segundo plano.
- Todas las deducciones de crédito utilizan libros de contabilidad inmutables + claves idempotentes.
- BYOK A los usuarios no se les deducirá la cuota de hosting del modelo, pero sí podrán deducir almacenamiento, memoria, archivos,Agent y créditos de funciones premium.
- Hosted El usuario retiene o autoriza previamente el modelo antes de solicitarlo y luego lo liquida en función del uso real. Cualquier exceso será reembolsado o compensado.
- El pago analógico es lo primero; pases alipay `PaymentProvider` Acceso a la interfaz, las credenciales de producción no ingresan a la base del código.

## El primer lote de parámetros ajustables en segundo plano.

Nombre del paquete, precio mensual, precio anual, período de prueba, cuota de mensajes,Token cuota,Agent tiempos, fotos/Tiempos de vídeo, almacenamiento,MCP Cantidad, número de concurrencias,BYOK interruptor,Hosted Conmutadores, políticas excedentes e indicadores de funciones.
