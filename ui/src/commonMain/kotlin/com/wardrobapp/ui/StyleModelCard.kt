package com.wardrobapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.StyleModelState
import com.wardrobapp.presentation.StyleModelState.Offer
import com.wardrobapp.presentation.StyleModelState.Status
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.action_retry
import com.wardrobapp.ui.resources.inspiration_model_notice
import com.wardrobapp.ui.resources.style_model_download
import com.wardrobapp.ui.resources.style_model_downloading
import com.wardrobapp.ui.resources.style_model_explain
import com.wardrobapp.ui.resources.style_model_explain_paired
import com.wardrobapp.ui.resources.style_model_failed
import com.wardrobapp.ui.resources.style_model_failed_reason
import com.wardrobapp.ui.resources.style_model_metered
import com.wardrobapp.ui.resources.style_model_ready
import com.wardrobapp.ui.resources.style_model_remove
import com.wardrobapp.ui.resources.settings_section_style_model
import com.wardrobapp.ui.resources.style_model_title
import org.jetbrains.compose.resources.stringResource

const val STYLE_MODEL_DOWNLOAD = "style-model-download"
const val STYLE_MODEL_CANCEL = "style-model-cancel"
const val STYLE_MODEL_REMOVE = "style-model-remove"
const val STYLE_MODEL_PROGRESS = "style-model-progress"
const val INSPIRATION_MODEL_NOTICE = "inspiration-model-notice"

/**
 * The style model, offered: what it is, what it costs, and the one thing to
 * do about it now.
 *
 * Written to be read before anybody taps anything. The download is 85 MB, so
 * the card says the size on the button itself, says it again on mobile data,
 * and says where it comes from and that photos go nowhere -- the questions a
 * careful person has before agreeing to it. On a phone paired with Home
 * Assistant it says the download is optional, since Home Assistant already
 * does the work and sync brings it here: nobody should spend 85 MB because
 * the app made it sound necessary when it was not.
 *
 * Nothing starts without the tap. Leaving the screen does not stop a
 * download that has started; Cancel does.
 */
@Composable
fun StyleModelCard(
    state: StyleModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.style_model_title), style = MaterialTheme.typography.titleSmall)

            val status = state.status
            when (state.offer) {
                Offer.DOWNLOAD, Offer.DOWNLOAD_BESIDE_HOME_ASSISTANT, Offer.RETRY -> {
                    Text(
                        stringResource(
                            if (state.paired) Res.string.style_model_explain_paired else Res.string.style_model_explain,
                            state.megabytes,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status is Status.Failed) {
                        Text(
                            status.message?.let { stringResource(Res.string.style_model_failed_reason, it) }
                                ?: stringResource(Res.string.style_model_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.metered) {
                        Text(
                            stringResource(Res.string.style_model_metered),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onDownload, modifier = Modifier.testTag(STYLE_MODEL_DOWNLOAD)) {
                        Text(
                            if (status is Status.Failed) {
                                stringResource(Res.string.action_retry)
                            } else {
                                stringResource(Res.string.style_model_download, state.megabytes)
                            },
                        )
                    }
                }

                Offer.DOWNLOADING -> {
                    Text(stringResource(Res.string.style_model_downloading), style = MaterialTheme.typography.bodyMedium)
                    val fraction = (status as? Status.Downloading)?.fraction
                    val bar = Modifier.fillMaxWidth().height(4.dp).testTag(STYLE_MODEL_PROGRESS)
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = bar)
                    } else {
                        LinearProgressIndicator(progress = { fraction }, drawStopIndicator = {}, modifier = bar)
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(STYLE_MODEL_CANCEL)) {
                            Text(stringResource(Res.string.action_cancel))
                        }
                    }
                }

                Offer.REMOVE -> {
                    Text(stringResource(Res.string.style_model_ready, state.megabytes), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onRemove, modifier = Modifier.testTag(STYLE_MODEL_REMOVE)) {
                        Text(stringResource(Res.string.style_model_remove))
                    }
                }
            }
        }
    }
}

/**
 * The card on the looks screen, with the sentence that matters there: until
 * the model is here, a saved look changes nothing. Said outright, because
 * the screen otherwise invites somebody to fill it with photos that would
 * quietly do nothing -- the one way this feature could disappoint without
 * saying why. Shown only where it is true; see [StyleModelState.showsOnLooks].
 */
@Composable
fun StyleModelLooksNotice(
    state: StyleModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp).testTag(INSPIRATION_MODEL_NOTICE)) {
        if (state.status !is Status.Downloading) {
            Text(
                stringResource(Res.string.inspiration_model_notice),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        StyleModelCard(state, onDownload, onCancel, onRemove)
    }
}

/**
 * The style model in Settings, heading included: the card in every state,
 * so the model can be downloaded there whether or not anybody visits the
 * looks screen, and removed to give the space back.
 */
@Composable
fun StyleModelSection(
    state: StyleModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Section(stringResource(Res.string.settings_section_style_model))
    StyleModelCard(state, onDownload, onCancel, onRemove, modifier = Modifier.padding(top = 8.dp))
}
