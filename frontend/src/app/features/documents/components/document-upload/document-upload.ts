import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import type { DocumentClassification, DocumentType } from '../../../../core/models/chat.model';
import type { UploadDocumentRequest } from '../../../../core/models/document.model';

const DOCUMENT_TYPES: readonly DocumentType[] = ['POLICY', 'CLAIMS_PROCEDURE', 'CORPORATE'];
const CLASSIFICATIONS: readonly DocumentClassification[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];

/**
 * Upload form (brief FASE 15 section 17): drag & drop or file picker, client-side extension
 * validation. File size is deliberately not validated client-side against a guessed limit - the
 * backend applies its own real multipart size limit and, if exceeded, the errorInterceptor
 * surfaces that failure the same way as any other API error (no invented threshold).
 */
@Component({
  selector: 'app-document-upload',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  templateUrl: './document-upload.html',
  styleUrl: './document-upload.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentUpload {
  private readonly formBuilder = inject(FormBuilder);

  readonly uploading = input(false);
  readonly uploadRequested = output<UploadDocumentRequest>();

  protected readonly documentTypes = DOCUMENT_TYPES;
  protected readonly classifications = CLASSIFICATIONS;

  protected readonly selectedFile = signal<File | null>(null);
  protected readonly isDragging = signal(false);
  protected readonly fileError = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    name: ['', Validators.required],
    type: this.formBuilder.nonNullable.control<DocumentType>('POLICY', Validators.required),
    classification: this.formBuilder.nonNullable.control<DocumentClassification>('INTERNAL', Validators.required),
    product: [''],
    country: [''],
    language: [''],
  });

  protected readonly canSubmit = computed(
    () => this.selectedFile() !== null && this.fileError() === null && !this.uploading(),
  );

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(true);
  }

  protected onDragLeave(): void {
    this.isDragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.setFile(file);
    }
  }

  protected onFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.setFile(file);
    }
  }

  private setFile(file: File): void {
    if (!file.name.toLowerCase().endsWith('.pdf')) {
      this.fileError.set('Only PDF files are supported.');
      this.selectedFile.set(null);
      return;
    }
    this.fileError.set(null);
    this.selectedFile.set(file);
    if (!this.form.controls.name.value) {
      this.form.controls.name.setValue(file.name.replace(/\.pdf$/i, ''));
    }
  }

  protected removeFile(): void {
    this.selectedFile.set(null);
    this.fileError.set(null);
  }

  protected submit(): void {
    const file = this.selectedFile();
    if (!file || this.form.invalid || !this.canSubmit()) {
      return;
    }
    const value = this.form.getRawValue();
    this.uploadRequested.emit({
      file,
      name: value.name,
      type: value.type,
      classification: value.classification,
      product: value.product || undefined,
      country: value.country || undefined,
      language: value.language || undefined,
    });
  }
}
