package app.pomoshnik.core

enum class CommandState(val title: String) {
    QUEUED("В очереди"),
    SENT("Передано"),
    DONE("Готово"),
    FAILED("Ошибка"),
    UNDONE("Отменено"),
}
