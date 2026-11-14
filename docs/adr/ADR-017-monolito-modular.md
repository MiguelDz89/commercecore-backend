# ADR-017: Monolito modular y organización por capacidades

Estado: aceptado

## Contexto

CommerceCore es un backend multiempresa que evolucionará hacia catálogos,
productos compuestos, compatibilidad vehicular y capacidades ERP. Se necesita
mantener límites claros entre funcionalidades sin introducir la complejidad de
desplegar servicios independientes.

## Decisión

CommerceCore será un monolito modular: una aplicación y una unidad de
despliegue, organizada bajo `com.commercecore.diaz` por capacidades de negocio.
Los módulos previstos son `tenant`, `identity`, `catalog`, `assembly`, `vehicle`,
`media`, `importing` y `audit`. Los paquetes se crean cuando contienen código.
`demo` es temporal y agrupa los endpoints de demostración existentes.

Cada módulo usa, cuando corresponda, estas capas internas:

- `api`: puntos de entrada y contratos de transporte; traduce solicitudes y
  respuestas sin exponer directamente entidades de persistencia.
- `application`: casos de uso, coordinación de transacciones y puertos necesarios
  para ejecutar las operaciones del módulo.
- `domain`: conceptos, invariantes y reglas de negocio propios del módulo.
- `infrastructure`: adaptadores de persistencia y de servicios externos, y demás
  implementaciones técnicas de los puertos.

## Reglas de dependencias y límites

Las dependencias se orientan hacia el dominio y los casos de uso. `api` llama a
`application`; `application` usa el dominio y define los puertos que necesita;
`infrastructure` implementa esos puertos. El dominio no depende de APIs REST,
Hibernate, Panache ni adaptadores de infraestructura. Las entidades JPA y los
contratos REST son representaciones separadas.

La colaboración entre módulos pasa por contratos explícitos del módulo dueño de
la capacidad. Un módulo no accede directamente a repositorios, entidades JPA,
adaptadores, paquetes `infrastructure` ni tablas internas de otro módulo. Se
evitan las dependencias circulares. Estas reglas se verifican mediante revisión
y, cuando exista código de negocio suficiente, pruebas arquitectónicas.

## Seguridad multiempresa y datos

La identidad y el contexto de empresa deben obtenerse de fuentes confiables y
verificarse en cada caso de uso y acceso a datos que lo requiera. Los módulos no
deben asumir que un identificador de empresa recibido del cliente implica
autorización. El aislamiento y la autorización se prueban entre empresas; las
decisiones concretas de propagación de contexto y de políticas de base de datos
se documentarán e implementarán en incrementos posteriores.

PostgreSQL, Hibernate ORM con Panache y Flyway proporcionan la persistencia. Las
migraciones versionadas son propiedad de Flyway. Las entidades JPA pertenecen a
la infraestructura del módulo propietario y no constituyen su API pública.

## Responsabilidades transversales

`shared` se limita a conceptos o contratos transversales estables, con más de un
consumidor y responsabilidad clara. No es un depósito genérico de utilidades,
entidades de negocio, repositorios ni implementaciones internas de módulos.
Un concepto con propietario claro permanece en su módulo.

`platform` agrupa capacidades técnicas transversales de la aplicación sin
propietario de negocio, como los health checks generales. No contiene reglas de
negocio ni sustituye los contratos de los módulos. Los módulos no deben depender
de detalles internos de otros módulos a través de `platform` o `shared`.

## Consecuencias

Los límites son más fáciles de revisar y permiten evolucionar módulos dentro de
una sola aplicación. La disciplina de dependencias requiere contratos explícitos
y pruebas de aislamiento. No se crean servicios independientes ni paquetes o
clases vacíos para anticipar funcionalidades.
