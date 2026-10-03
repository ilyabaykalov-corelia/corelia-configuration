# corelia-configuration

Библиотека загрузки, валидации и компиляции provider-neutral customer
configuration. Её используют сервисы для неизменяемого runtime registry и
`scripts/compile-config.sh` для подготовки release; самостоятельного сервера
в модуле нет.

`ConfigurationLoader` принимает Schema V2 и V3, проверяет manifest, источники,
grants, provider bindings и branding. `CoreliaConfigurationCompiler` проверяет
пакет, копирует разрешённые resources в новый каталог `corelia/`, создаёт
runtime `branding/branding.json` и `manifest.json` release.

```bash
mvn -pl corelia-configuration -am test
./scripts/compile-config.sh /path/to/source /path/to/new-release
```

Выходной каталог должен быть новым и лежать вне source package. Формат и
ограничения: [configuration](../docs/configuration.md).
