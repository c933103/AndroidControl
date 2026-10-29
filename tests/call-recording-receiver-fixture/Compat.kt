// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package androidx.core.content
import android.content.Context
import org.androidcontrol.app.recording.Fixture
object ContextCompat { fun checkSelfPermission(context: Context, permission: String) = Fixture.permission }
