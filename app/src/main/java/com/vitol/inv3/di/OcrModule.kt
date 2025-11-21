package com.vitol.inv3.di

import android.app.Application
import com.vitol.inv3.ocr.InvoiceTextRecognizer
import com.vitol.inv3.ocr.InvoiceValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun provideTextRecognizer(app: Application): InvoiceTextRecognizer = InvoiceTextRecognizer(app)
    
    @Provides
    @Singleton
    fun provideInvoiceValidator(): InvoiceValidator = InvoiceValidator()
}

