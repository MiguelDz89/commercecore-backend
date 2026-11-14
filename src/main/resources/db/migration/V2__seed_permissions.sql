-- Permisos globales del MVP: no crea usuarios ni asigna roles entre empresas.
INSERT INTO permission (permission_code, description) VALUES
 ('TENANT_READ', 'Consultar empresa y configuración'),
 ('TENANT_MANAGE', 'Administrar empresa'),
 ('USER_MANAGE', 'Administrar membresías y roles'),
 ('PRODUCT_READ', 'Consultar productos'),
 ('PRODUCT_CREATE', 'Crear productos'),
 ('PRODUCT_UPDATE', 'Editar productos'),
 ('PRODUCT_PRICE_UPDATE', 'Modificar precio de venta'),
 ('PRODUCT_DISABLE', 'Desactivar productos'),
 ('PRODUCT_IMPORT', 'Importar catálogo'),
 ('PRODUCT_IMAGE_MANAGE', 'Administrar imágenes de productos'),
 ('CATEGORY_MANAGE', 'Administrar categorías'),
 ('PRODUCT_BOM_MANAGE', 'Administrar composiciones y versiones BOM'),
 ('VEHICLE_COMPATIBILITY_MANAGE', 'Administrar modelos y compatibilidades vehiculares'),
 ('AUDIT_READ', 'Consultar auditoría de negocio')
ON CONFLICT (permission_code) DO NOTHING;
