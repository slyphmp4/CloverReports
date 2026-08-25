package com.slyph.cloverreports.export;

import com.slyph.cloverreports.models.ReportLog;

import java.io.IOException;
import java.io.Writer;

interface LogExportWriter {

    void begin(Writer writer) throws IOException;

    void write(Writer writer, ReportLog log) throws IOException;

    void finish(Writer writer) throws IOException;
}
