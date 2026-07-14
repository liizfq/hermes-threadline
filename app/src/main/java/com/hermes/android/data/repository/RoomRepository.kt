package com.hermes.android.data.repository

import android.util.Log
import kotlinx.coroutines.delay
import org.matrix.rustcomponents.sdk.Room
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoomRepo"

interface RoomRepository {
    suspend fun getRoom(roomId: String): Room?
    suspend fun verifyRoomAccess(roomId: String): Boolean
}

@Singleton
class RoomRepositoryImpl @Inject constructor(
    private val matrixRepository: MatrixRepository
) : RoomRepository {

    override suspend fun getRoom(roomId: String): Room? {
        // Retry up to 5 times with 1s delay to wait for sync to populate rooms
        repeat(5) { attempt ->
            try {
                val room = matrixRepository.getClient()?.getRoom(roomId)
                if (room != null) {
                    if (attempt > 0) Log.d(TAG, "Room found after ${attempt + 1} attempts")
                    return room
                }
            } catch (e: Exception) {
                Log.d(TAG, "getRoom attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < 4) {
                Log.d(TAG, "Room not found, retrying in 1s (attempt ${attempt + 1}/5)")
                delay(1000)
            }
        }
        Log.w(TAG, "Room $roomId not found after 5 attempts")
        return null
    }

    override suspend fun verifyRoomAccess(roomId: String): Boolean {
        return getRoom(roomId) != null
    }
}
