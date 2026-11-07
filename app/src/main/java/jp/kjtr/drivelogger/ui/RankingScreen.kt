package jp.kjtr.drivelogger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.ranking.RankingSpec

/** オンラインランキングの画面（0.12.0。ホーム・まとめから。中身は RankingPane。DESIGN.md §4.12）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingScreen(vm: MainViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ranking_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            RankingPane(vm.ranking, RankingSpec.BOARDS, onSettings)
        }
    }
}
