# Cuentas Claras

App de Android para controlar tus gastos e ingresos, con categorías, gastos fijos, presupuesto mensual, seguimiento de inversiones, gráficos y avisos (notificaciones) cuando te acercas a tu límite de gasto.

## Cómo conseguir el APK

Cada vez que se sube un cambio a este repositorio, GitHub compila la app automáticamente (pestaña **Actions**, tarda unos 3–5 minutos).

1. Desde el móvil, abre este repositorio en GitHub y entra en **Releases** (columna derecha, o añade `/releases` a la dirección).
2. En la versión más reciente, toca **CuentasClaras.apk** para descargarlo.
3. Abre el archivo descargado. Android te pedirá permiso para instalar apps de origen desconocido: concédelo a tu navegador o a "Archivos".
4. Instala y abre **Cuentas Claras**. Acepta el permiso de notificaciones para recibir los avisos del presupuesto.

Para lanzar una compilación a mano: pestaña **Actions** → **Compilar APK** → **Run workflow**.

## Actualizar la app

Las nuevas versiones se instalan encima de la anterior sin perder tus datos (todas usan la misma firma). Aun así, haz de vez en cuando una copia desde **Ajustes (engranaje) → Exportar copia**: si desinstalas la app, los datos se borran.

## Con Android Studio (opcional)

Abre esta carpeta con Android Studio y usa **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, o conecta el móvil y pulsa **Run**.

## Estructura

- `app/src/main/assets/index.html`: toda la interfaz, con sus gráficos y animaciones. Funciona sin conexión.
- `app/src/main/assets/fonts/`: la tipografía Mona Sans (licencia SIL Open Font License, en `OFL.txt`).
- `app/src/main/java/.../MainActivity.java`: contenedor nativo, notificaciones y copias de seguridad.
- `.github/workflows/compilar-apk.yml`: compilación automática en GitHub.
