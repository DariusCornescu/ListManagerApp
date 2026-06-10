package com.darius.listmanager.workspace

import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.data.workspace.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeStore : WorkspaceStore {
    var saved: String? = null
    override fun read(): String? = saved
    override fun write(value: String?) { saved = value }
}

class WorkspaceManagerTest {

    @Test
    fun `defaults to Personal when nothing persisted`() {
        val manager = WorkspaceManager(FakeStore())
        assertEquals(Workspace.Personal, manager.currentWorkspace.value)
    }

    @Test
    fun `switchTo team persists and updates flow`() {
        val store = FakeStore()
        val manager = WorkspaceManager(store)

        manager.switchTo(Workspace.Team(id = 7, name = "Depot"))

        assertEquals(Workspace.Team(7, "Depot"), manager.currentWorkspace.value)
        assertEquals("7:Depot", store.saved)
    }

    @Test
    fun `restores persisted team workspace on construction`() {
        val store = FakeStore().apply { saved = "7:Depot" }
        val manager = WorkspaceManager(store)
        assertEquals(Workspace.Team(7, "Depot"), manager.currentWorkspace.value)
    }

    @Test
    fun `restores Personal when persisted value is null`() {
        val store = FakeStore().apply { saved = null }
        assertEquals(Workspace.Personal, WorkspaceManager(store).currentWorkspace.value)
    }

    @Test
    fun `team name containing colon round-trips`() {
        val store = FakeStore()
        val manager = WorkspaceManager(store)
        manager.switchTo(Workspace.Team(3, "A:B Team"))
        assertEquals(Workspace.Team(3, "A:B Team"), WorkspaceManager(store).currentWorkspace.value)
    }

    @Test
    fun `corrupt persisted value falls back to Personal`() {
        val store = FakeStore().apply { saved = "not-a-number:X" }
        assertEquals(Workspace.Personal, WorkspaceManager(store).currentWorkspace.value)
    }

    @Test
    fun `fallbackToPersonal switches and persists`() {
        val store = FakeStore()
        val manager = WorkspaceManager(store)
        manager.switchTo(Workspace.Team(7, "Depot"))

        manager.fallbackToPersonal()

        assertEquals(Workspace.Personal, manager.currentWorkspace.value)
        assertEquals(null, store.saved)
    }

    @Test
    fun `teamIdOrNull returns id for team and null for personal`() {
        assertEquals(null, Workspace.Personal.teamIdOrNull)
        assertEquals(7L, Workspace.Team(7, "Depot").teamIdOrNull)
    }
}
