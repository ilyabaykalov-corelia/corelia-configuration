package ru.corelia.configuration;

import java.util.List;

/** Конфигурация маршрутов Kafka для универсального создания документов. */
public record KafkaDocumentCreationConfiguration(String consumerGroup, Actor actor, List<Route> routes) {
    public KafkaDocumentCreationConfiguration {
        routes = List.copyOf(routes);
    }

    /** Технический исполнитель, от имени которого вызывается обычный сценарий создания. */
    public record Actor(String id, String login, String fullName, String email, List<String> roles, String taskUsername) {
        public Actor {
            roles = List.copyOf(roles);
        }
    }

    /** Связь topic с видом документа из customer configuration. */
    public record Route(String topic, String typeCode) {}
}
