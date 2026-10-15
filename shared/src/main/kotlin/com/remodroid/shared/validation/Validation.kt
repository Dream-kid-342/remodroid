package com.remodroid.shared.validation

/** Client-side input checks. They improve UX only; the server and host re-validate everything. */
object Validation {
    const val ID_LENGTH = 9
    const val PASSWORD_LENGTH = 8
    private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    fun normalizeId(input: String): String = input.filter { it.isDigit() }

    fun isValidId(input: String): Boolean {
        val id = normalizeId(input)
        return id.length == ID_LENGTH && id.first() != '0' && input.all { it.isDigit() || it == ' ' || it == '-' }
    }

    /** "123456789" -> "123 456 789" */
    fun formatId(id: String): String = normalizeId(id).chunked(3).joinToString(" ")

    fun normalizePassword(input: String): String = input.filterNot { it.isWhitespace() }.lowercase()

    fun isValidPassword(input: String): Boolean {
        val p = normalizePassword(input)
        return p.length == PASSWORD_LENGTH && p.all { it in PASSWORD_ALPHABET }
    }

    sealed interface Problem {
        data object IdEmpty : Problem
        data object IdInvalid : Problem
        data object PasswordEmpty : Problem
        data object PasswordInvalid : Problem
    }

    fun validateConnectForm(id: String, password: String): List<Problem> = buildList {
        when {
            id.isBlank() -> add(Problem.IdEmpty)
            !isValidId(id) -> add(Problem.IdInvalid)
        }
        when {
            password.isBlank() -> add(Problem.PasswordEmpty)
            !isValidPassword(password) -> add(Problem.PasswordInvalid)
        }
    }

    fun describe(problem: Problem): String = when (problem) {
        Problem.IdEmpty -> "Enter the partner's 9-digit ID."
        Problem.IdInvalid -> "A RemoDroid ID has 9 digits and does not start with 0."
        Problem.PasswordEmpty -> "Enter the partner's temporary password."
        Problem.PasswordInvalid -> "The temporary password has 8 letters and digits."
    }

    /** Display names are shown on the other device; strip control/format characters and limit length. */
    fun sanitizeDisplayName(input: String): String? {
        val cleaned = input.filter { ch ->
            val type = Character.getType(ch)
            type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()
        }.trim().take(64)
        return cleaned.ifEmpty { null }
    }
}
