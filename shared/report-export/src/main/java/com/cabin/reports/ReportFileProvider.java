package com.cabin.reports;

/** Dedicated provider so reports never expose album art, firmware or arbitrary app files. */
public final class ReportFileProvider extends androidx.core.content.FileProvider { }
