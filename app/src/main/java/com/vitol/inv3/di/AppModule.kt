package com.vitol.inv3.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Application is automatically provided by Hilt, no need to provide it manually
}

