/*
 * Copyright 2023 Stax
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hover.stax.storage.user.dao.user

import androidx.room.Dao
import androidx.room.Query
import com.hover.stax.storage.user.dao.BaseDao
import com.hover.stax.storage.user.entity.StaxUser
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao : BaseDao<StaxUser> {
    @Query("SELECT * FROM stax_users LIMIT 1")
    fun getUserAsync(): Flow<StaxUser>

    @Query("SELECT * FROM stax_users LIMIT 1")
    suspend fun getUser(): StaxUser?
}