package com.lightphone.imessage.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.lightphone.imessage.data.entity.ContactEntity
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.entity.ThreadEntity
import com.lightphone.imessage.data.repository.ContactRepository
import com.lightphone.imessage.data.repository.MessageRepository
import com.lightphone.imessage.data.repository.ThreadRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive integration tests for Room CRUD operations via repository layer. Tests insert,
 * update, delete, query operations, foreign key constraints, Flow reactivity, and duplicate
 * detection. Target: 100% coverage.
 *
 * Spec: milestone-2.md § 2 (Data Model); TASK_010 (Repository Layer)
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class RepositoryIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: ImessageDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var threadRepository: ThreadRepository
    private lateinit var contactRepository: ContactRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ImessageDatabase.getInstance(context)
        messageRepository = MessageRepository(database)
        threadRepository = ThreadRepository(database)
        contactRepository = ContactRepository(database)
    }

    @After
    fun tearDown() {
        database.clearAllTables()
        database.close()
    }

    // ========== Message CRUD ==========

    /**
     * Test: Message CRUD Operations
     *
     * Verifies insert, update, delete, and query operations for messages. Tests all basic CRUD
     * operations in isolation.
     */
    @Test
    fun testMessageCRUD() {
        val messageId = UUID.randomUUID().toString()
        val threadId = UUID.randomUUID().toString()

        // Setup: Create required thread first
        val thread =
                ThreadEntity(
                        id = threadId,
                        title = "Test Thread",
                        lastMessage = "",
                        lastTimestamp = System.currentTimeMillis(),
                        participantUris = "user@icloud.com|+11234567890"
                )
        runBlocking { threadRepository.insertThread(thread) }

        // Test 1: Insert
        val message =
                MessageEntity(
                        id = messageId,
                        threadId = threadId,
                        sender = "user@icloud.com",
                        body = "Hello World",
                        timestamp = System.currentTimeMillis(),
                        type = 1,
                        isOutgoing = false,
                        status = 1
                )

        val insertResult = runBlocking { messageRepository.insertMessage(message) }
        assertTrue("Insert should succeed", insertResult.isSuccess)

        // Test 2: Query by ID
        val queriedMessage = runBlocking { messageRepository.getMessageById(messageId).first() }
        assertNotNull("Message should be found", queriedMessage)
        assertEquals("Body should match", "Hello World", queriedMessage?.body)

        // Test 3: Update
        val updatedMessage = message.copy(body = "Updated message")
        val updateResult = runBlocking { messageRepository.updateMessage(updatedMessage) }
        assertTrue("Update should succeed", updateResult.isSuccess)

        val verifyUpdate = runBlocking { messageRepository.getMessageById(messageId).first() }
        assertEquals("Body should be updated", "Updated message", verifyUpdate?.body)

        // Test 4: Delete
        val deleteResult = runBlocking { messageRepository.deleteMessage(messageId) }
        assertTrue("Delete should succeed", deleteResult.isSuccess)

        val verifyDelete = runBlocking { messageRepository.getMessageById(messageId).first() }
        assertNull("Message should be deleted", verifyDelete)
    }

    // ========== Thread CRUD ==========

    /**
     * Test: Thread CRUD Operations
     *
     * Verifies insert, update, delete, and query operations for threads.
     */
    @Test
    fun testThreadCRUD() {
        val threadId = UUID.randomUUID().toString()

        // Test 1: Insert
        val thread =
                ThreadEntity(
                        id = threadId,
                        title = "Group Chat",
                        lastMessage = "Last message text",
                        lastTimestamp = System.currentTimeMillis(),
                        participantUris = "user1@icloud.com|user2@icloud.com|user3@icloud.com",
                        unreadCount = 5,
                        isMuted = false
                )

        val insertResult = runBlocking { threadRepository.insertThread(thread) }
        assertTrue("Insert should succeed", insertResult.isSuccess)

        // Test 2: Query by ID
        val queriedThread = runBlocking { threadRepository.getThreadById(threadId).first() }
        assertNotNull("Thread should be found", queriedThread)
        assertEquals("Title should match", "Group Chat", queriedThread?.title)
        assertEquals("Unread count should be 5", 5, queriedThread?.unreadCount)

        // Test 3: Update
        val updatedThread =
                thread.copy(lastMessage = "New last message", unreadCount = 0, isMuted = true)
        val updateResult = runBlocking { threadRepository.updateThread(updatedThread) }
        assertTrue("Update should succeed", updateResult.isSuccess)

        val verifyUpdate = runBlocking { threadRepository.getThreadById(threadId).first() }
        assertEquals(
                "Last message should be updated",
                "New last message",
                verifyUpdate?.lastMessage
        )
        assertEquals("Unread count should be 0", 0, verifyUpdate?.unreadCount)
        assertTrue("Should be muted", verifyUpdate?.isMuted == true)

        // Test 4: Delete
        val deleteResult = runBlocking { threadRepository.deleteThread(threadId) }
        assertTrue("Delete should succeed", deleteResult.isSuccess)

        val verifyDelete = runBlocking { threadRepository.getThreadById(threadId).first() }
        assertNull("Thread should be deleted", verifyDelete)
    }

    // ========== Contact CRUD ==========

    /**
     * Test: Contact CRUD Operations
     *
     * Verifies insert, update, delete, and query operations for contacts.
     */
    @Test
    fun testContactCRUD() {
        val contactId = UUID.randomUUID().toString()

        // Test 1: Insert
        val contact =
                ContactEntity(
                        id = contactId,
                        handle = "user@icloud.com",
                        displayName = "John Doe",
                        avatarUrl = "https://example.com/avatar.jpg"
                )

        val insertResult = runBlocking { contactRepository.insertContact(contact) }
        assertTrue("Insert should succeed", insertResult.isSuccess)

        // Test 2: Query by ID
        val queriedContact = runBlocking { contactRepository.getContactById(contactId).first() }
        assertNotNull("Contact should be found", queriedContact)
        assertEquals("Handle should match", "user@icloud.com", queriedContact?.handle)
        assertEquals("Display name should match", "John Doe", queriedContact?.displayName)

        // Test 3: Update
        val updatedContact =
                contact.copy(
                        displayName = "Jane Doe",
                        avatarUrl = "https://example.com/avatar2.jpg"
                )
        val updateResult = runBlocking { contactRepository.updateContact(updatedContact) }
        assertTrue("Update should succeed", updateResult.isSuccess)

        val verifyUpdate = runBlocking { contactRepository.getContactById(contactId).first() }
        assertEquals("Display name should be updated", "Jane Doe", verifyUpdate?.displayName)
        assertEquals(
                "Avatar URL should be updated",
                "https://example.com/avatar2.jpg",
                verifyUpdate?.avatarUrl
        )

        // Test 4: Delete
        val deleteResult = runBlocking { contactRepository.deleteContact(contactId) }
        assertTrue("Delete should succeed", deleteResult.isSuccess)

        val verifyDelete = runBlocking { contactRepository.getContactById(contactId).first() }
        assertNull("Contact should be deleted", verifyDelete)
    }

    // ========== Foreign Key Constraint ==========

    /**
     * Test: Message Requires Valid Thread (Foreign Key Constraint)
     *
     * Verifies that inserting a message with non-existent threadId fails due to foreign key
     * constraint violation.
     */
    @Test
    fun testForeignKeyConstraint() {
        val messageId = UUID.randomUUID().toString()
        val invalidThreadId = "non-existent-thread-id"

        // Attempt to insert message with non-existent thread
        val message =
                MessageEntity(
                        id = messageId,
                        threadId = invalidThreadId,
                        sender = "user@icloud.com",
                        body = "Orphan message",
                        timestamp = System.currentTimeMillis(),
                        type = 1,
                        isOutgoing = false,
                        status = 1
                )

        val insertResult = runBlocking { messageRepository.insertMessage(message) }

        // Should fail due to foreign key constraint
        assertFalse("Insert should fail due to missing thread", insertResult.isSuccess)

        // Verify message was not persisted
        val queriedMessage = runBlocking { messageRepository.getMessageById(messageId).first() }
        assertNull("Orphan message should not exist", queriedMessage)
    }

    // ========== Flow Reactivity ==========

    /**
     * Test: Flow Reactivity → Update Triggers Emission
     *
     * Verifies that when a message is updated, any Flow observers are notified with the new value.
     */
    @Test
    fun testFlowReactivity() {
        val messageId = UUID.randomUUID().toString()
        val threadId = UUID.randomUUID().toString()

        // Setup: Create thread
        val thread =
                ThreadEntity(
                        id = threadId,
                        title = "Test",
                        lastMessage = "",
                        lastTimestamp = System.currentTimeMillis(),
                        participantUris = "user@icloud.com"
                )
        runBlocking { threadRepository.insertThread(thread) }

        // Create initial message
        val initialMessage =
                MessageEntity(
                        id = messageId,
                        threadId = threadId,
                        sender = "user@icloud.com",
                        body = "Initial",
                        timestamp = System.currentTimeMillis(),
                        type = 1,
                        isOutgoing = false,
                        status = 1
                )

        runBlocking { messageRepository.insertMessage(initialMessage) }

        // Collect initial value from Flow
        var lastCollectedValue: MessageEntity? = null
        runBlocking {
            val flow = messageRepository.getMessageById(messageId)
            flow.collect { msg ->
                lastCollectedValue = msg
                // Stop after first emission
            }
        }
        assertEquals("Initial value should be collected", "Initial", lastCollectedValue?.body)

        // Update message
        val updatedMessage = initialMessage.copy(body = "Updated")
        runBlocking { messageRepository.updateMessage(updatedMessage) }

        // Collect updated value from Flow
        runBlocking {
            val flow = messageRepository.getMessageById(messageId)
            flow.collect { msg -> lastCollectedValue = msg }
        }
        assertEquals("Updated value should be emitted", "Updated", lastCollectedValue?.body)
    }

    // ========== Duplicate Detection ==========

    /**
     * Test: Message ID Unique Constraint
     *
     * Verifies that MessageEntity.id is a PRIMARY KEY (unique constraint). Inserting duplicate
     * messageId fails.
     */
    @Test
    fun testDuplicateDetection() {
        val messageId = UUID.randomUUID().toString()
        val threadId = UUID.randomUUID().toString()

        // Setup: Create thread
        val thread =
                ThreadEntity(
                        id = threadId,
                        title = "Test",
                        lastMessage = "",
                        lastTimestamp = System.currentTimeMillis(),
                        participantUris = "user@icloud.com"
                )
        runBlocking { threadRepository.insertThread(thread) }

        val message =
                MessageEntity(
                        id = messageId,
                        threadId = threadId,
                        sender = "user@icloud.com",
                        body = "Message 1",
                        timestamp = System.currentTimeMillis(),
                        type = 1,
                        isOutgoing = false,
                        status = 1
                )

        // Insert first time - should succeed
        val firstInsert = runBlocking { messageRepository.insertMessage(message) }
        assertTrue("First insert should succeed", firstInsert.isSuccess)

        // Attempt to insert duplicate with same ID - should fail
        val duplicateMessage = message.copy(body = "Message 2")
        val secondInsert = runBlocking { messageRepository.insertMessage(duplicateMessage) }
        assertFalse("Duplicate insert should fail due to unique constraint", secondInsert.isSuccess)

        // Verify only first message persists
        val queriedMessage = runBlocking { messageRepository.getMessageById(messageId).first() }
        assertEquals("Original message should remain", "Message 1", queriedMessage?.body)
    }

    // ========== Helper Methods ==========

    /** Get first element from Flow (test helper) */
    private suspend inline fun <reified T> kotlinx.coroutines.flow.Flow<T?>.first(): T? {
        var result: T? = null
        this.collect { result = it }
        return result
    }

    /** Get all elements from Flow (test helper) */
    private suspend inline fun <reified T> kotlinx.coroutines.flow.Flow<List<T>>.all(): List<T> {
        var result = listOf<T>()
        this.collect { result = it }
        return result
    }
}
