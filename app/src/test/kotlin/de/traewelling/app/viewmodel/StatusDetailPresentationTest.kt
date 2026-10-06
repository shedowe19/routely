package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import org.junit.Assert.*
import org.junit.Test

class StatusDetailPresentationTest {
    private fun status(id: Int = 42) = Gson().fromJson("{\"id\":$id,\"body\":\"server text\"}", Status::class.java)

    @Test fun recreatedScreenRetainsItsUnsavedTextAndOpeningSnapshot() {
        val presentation = StatusDetailPresentation()
        val first = presentation.attach(42)
        val opening = status()
        presentation.state.value = StatusDetailUiState(status = opening, isEditing = true,
            editInitialStatus = opening, editBody = "unsaved draft", editArrival = "2026-10-06T12:00:00Z",
            editArrivalManuallyChanged = true)
        val retained = presentation.state.value
        val generation = presentation.generation
        assertTrue(presentation.detach(first))
        assertFalse(presentation.isObserved)
        val recreated = presentation.attach(42)
        assertFalse(recreated.changedStatus)
        assertSame(retained, presentation.state.value)
        assertEquals(generation, presentation.generation)
        assertEquals("unsaved draft", presentation.state.value.editBody)
    }

    @Test fun oldCompositionDisposalCannotStopTheNewObserverOrInvalidateItsWrite() {
        val presentation = StatusDetailPresentation()
        val old = presentation.attach(42)
        presentation.state.value = StatusDetailUiState(status = status(), isEditing = true,
            isUpdating = true, editBody = "submitted draft")
        val writeGeneration = presentation.generation
        val replacement = presentation.attach(42)
        assertFalse(presentation.detach(old))
        assertTrue(presentation.isObserved)
        assertEquals(writeGeneration, presentation.generation)
        assertTrue(presentation.state.value.isUpdating)
        assertTrue(presentation.detach(replacement))
        // A successful response still belongs to the same retained status after disposal.
        assertEquals(42, presentation.statusId)
        assertEquals(writeGeneration, presentation.generation)
    }

    @Test fun deletionCompletionSurvivesDisposalUntilTheCurrentScreenCanNavigate() {
        val presentation = StatusDetailPresentation()
        val first = presentation.attach(42)
        presentation.state.value = StatusDetailUiState(status = status(), isDeleting = true)
        presentation.detach(first)
        presentation.state.value = presentation.state.value.copy(isDeleting = false, deletedStatusId = 42)
        presentation.attach(42)
        assertEquals(42, presentation.state.value.deletedStatusId)
        assertTrue(presentation.clear(42))
        assertNull(presentation.state.value.deletedStatusId)
    }

    @Test fun aDifferentStatusOrExplicitLeaveDiscardsTheDraftAndInvalidatesOldResponses() {
        val presentation = StatusDetailPresentation()
        val old = presentation.attach(42)
        presentation.state.value = StatusDetailUiState(status = status(), isEditing = true, editBody = "draft")
        val previousGeneration = presentation.generation
        val changed = presentation.attach(43)
        assertTrue(changed.changedStatus)
        assertTrue(presentation.generation > previousGeneration)
        assertFalse(presentation.state.value.isEditing)
        assertEquals("", presentation.state.value.editBody)
        assertFalse(presentation.detach(old))
        assertFalse(presentation.clear(42))
        assertTrue(presentation.isObserved)
        assertTrue(presentation.clear(43))
        assertNull(presentation.statusId)
        assertFalse(presentation.isObserved)
    }

    @Test fun immediateBackCannotLeaveAWriteBeforeCompositionHasObservedItsBusyState() {
        for (deleting in listOf(false, true)) {
            val presentation = StatusDetailPresentation()
            presentation.attach(42)
            val generation = presentation.generation
            presentation.state.value = StatusDetailUiState(status = status(),
                isUpdating = !deleting, isDeleting = deleting)
            assertFalse(presentation.leave(42))
            assertEquals(42, presentation.statusId)
            assertEquals(generation, presentation.generation)
            assertTrue(presentation.isObserved)
            presentation.state.value = presentation.state.value.copy(isUpdating = false, isDeleting = false)
            assertTrue(presentation.leave(42))
            assertNull(presentation.statusId)
        }
    }
}
