# Документация configuration

Модуль не содержит customer configuration и не должен получать конкретные
типы документов из production-кода. Загруженный `DocumentTypeCatalog` даёт
сервисам коды и names, а provider bindings остаются входом соответствующего
adapter.

Компилятор не устанавливает release и не выполняет BPMN: он валидирует и
готовит файловый артефакт. Deployment монтирует готовый каталог в
`/opt/corelia/config` read-only. См. [формат V2/V3](../../docs/configuration.md)
и [branding](../../docs/branding.md).
