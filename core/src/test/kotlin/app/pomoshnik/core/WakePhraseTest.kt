package app.pomoshnik.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakePhraseTest {
    @Test fun prefixCanBeFollowedByACommand() {
        assertEquals("поставь будильник на 10:30", WakePhrase.commandAfter("Слушай меня, поставь будильник на 10:30"))
        assertEquals("", WakePhrase.commandAfter("слушай меня"))
    }

    @Test fun phraseMustStartTheUtteranceAndEndOnAWordBoundary() {
        assertNull(WakePhrase.commandAfter("он сказал слушай меня"))
        assertNull(WakePhrase.commandAfter("слушай менять"))
    }
}
