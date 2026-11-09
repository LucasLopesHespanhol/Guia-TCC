package br.edu.unesc.tcc.eda_task_api.events;

public enum TaskEventType {
    CREATION_REQUEST_REGISTERED,
    UPDATE_REQUEST_REGISTERED,
    DELETION_REQUEST_REGISTERED,
    TASK_CREATED,
    TASK_UPDATED,
    TASK_DELETED;

    public boolean isRegistration() {
        return this == CREATION_REQUEST_REGISTERED
                || this == UPDATE_REQUEST_REGISTERED
                || this == DELETION_REQUEST_REGISTERED;
    }

    public String routingKey() {
        return switch (this) {
            case CREATION_REQUEST_REGISTERED -> "submission.creation.registered";
            case UPDATE_REQUEST_REGISTERED -> "submission.update.registered";
            case DELETION_REQUEST_REGISTERED -> "submission.deletion.registered";
            case TASK_CREATED -> "task.created";
            case TASK_UPDATED -> "task.updated";
            case TASK_DELETED -> "task.deleted";
        };
    }
}
