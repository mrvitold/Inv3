package com.vitol.inv3.di

import android.app.Application
import com.vitol.inv3.data.local.TemplateStore
import com.vitol.inv3.ocr.TemplateLearner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TemplateModule {
    @Provides
    @Singleton
    fun provideTemplateStore(app: Application): TemplateStore = TemplateStore(app)
    
    @Provides
    @Singleton
    fun provideTemplateLearner(
        templateStore: TemplateStore,
        app: Application
    ): TemplateLearner = TemplateLearner(templateStore, app)
}

