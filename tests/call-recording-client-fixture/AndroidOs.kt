// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.os
import java.io.FileDescriptor
class ParcelFileDescriptor(val fileDescriptor:FileDescriptor) { fun close() {} }
