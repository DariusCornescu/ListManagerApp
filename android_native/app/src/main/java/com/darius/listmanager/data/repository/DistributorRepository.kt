package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.DistributorDao
import com.darius.listmanager.data.local.entity.DistributorEntity
import kotlinx.coroutines.flow.Flow

class DistributorRepository (private val dao: DistributorDao){

    fun getAllFlow() : Flow<List<DistributorEntity>> =dao.getAllFlow()

    suspend fun getAll(): List<DistributorEntity> = dao.getAll()

    suspend fun getById(id: Long): DistributorEntity? = dao.getById(id)

    suspend fun getByName(name: String): DistributorEntity? = dao.getByName(name)

    suspend fun insert(distributor: DistributorEntity): Long = dao.insert(distributor)

    suspend fun update(distributor: DistributorEntity) = dao.update(distributor)

    suspend fun delete(distributor: DistributorEntity) = dao.delete(distributor)

    suspend fun upsertByName(name: String, contactInfo: String? = null): Long {
        return dao.upsertByName(name, contactInfo)
    }
}