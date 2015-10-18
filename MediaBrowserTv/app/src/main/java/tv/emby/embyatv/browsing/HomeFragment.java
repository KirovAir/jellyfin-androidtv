package tv.emby.embyatv.browsing;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.HeaderItem;
import android.support.v17.leanback.widget.ListRow;
import android.support.v17.leanback.widget.OnItemViewClickedListener;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowPresenter;
import android.widget.Toast;

import java.io.IOException;

import mediabrowser.apiinteraction.EmptyResponse;
import mediabrowser.apiinteraction.Response;
import mediabrowser.model.entities.SortOrder;
import mediabrowser.model.livetv.RecommendedProgramQuery;
import mediabrowser.model.querying.ItemFields;
import mediabrowser.model.querying.ItemFilter;
import mediabrowser.model.querying.ItemSortBy;
import mediabrowser.model.querying.ItemsResult;
import mediabrowser.model.querying.NextUpQuery;
import tv.emby.embyatv.integration.RecommendationManager;
import tv.emby.embyatv.model.ChangeTriggerType;
import tv.emby.embyatv.startup.LogonCredentials;
import tv.emby.embyatv.ui.GridButton;
import tv.emby.embyatv.R;
import tv.emby.embyatv.TvApp;
import tv.emby.embyatv.util.Utils;
import tv.emby.embyatv.presentation.GridButtonPresenter;
import tv.emby.embyatv.querying.StdItemQuery;
import tv.emby.embyatv.querying.ViewQuery;
import tv.emby.embyatv.settings.SettingsActivity;
import tv.emby.embyatv.startup.SelectUserActivity;
import tv.emby.embyatv.validation.UnlockActivity;

/**
 * Created by Eric on 12/4/2014.
 */
public class HomeFragment extends StdBrowseFragment {
    private static final int LOGOUT = 0;
    private static final int SETTINGS = 1;
    private static final int REPORT = 2;
    private static final int UNLOCK = 3;
    private static final int LOGOUT_CONNECT = 4;

    private ArrayObjectAdapter toolsRow;
    private GridButton unlockButton;
    private GridButton sendLogsButton;
    private GridButton premiereButton;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {

        MainTitle = this.getString(R.string.home_title);
        //Validate the app
        TvApp.getApplication().validate();

        super.onActivityCreated(savedInstanceState);

        //Save last login so we can get back proper context on entry
        try {
            Utils.SaveLoginCredentials(new LogonCredentials(TvApp.getApplication().getApiClient().getServerInfo(), TvApp.getApplication().getCurrentUser()), "tv.emby.lastlogin.json");
        } catch (IOException e) {
            TvApp.getApplication().getLogger().ErrorException("Unable to save login creds", e);
        }

        //Init recommendations
        RecommendationManager.init();

        //Get auto bitrate
        TvApp.getApplication().determineAutoBitrate();

    }

    @Override
    public void onResume() {
        super.onResume();

        //if we were locked before and have just unlocked, remove the button
        if (unlockButton != null && (TvApp.getApplication().isRegistered() || TvApp.getApplication().isPaid())) {
            toolsRow.remove(unlockButton);
            if (!TvApp.getApplication().isRegistered()) {
                premiereButton = new GridButton(UNLOCK, mApplication.getString(R.string.btn_emby_premiere), R.drawable.embyicon);
                toolsRow.add(premiereButton);
            }
        } else {
            if (premiereButton != null && TvApp.getApplication().isRegistered()) {
                toolsRow.remove(premiereButton);
            }
        }
        addLogsButton();
        premiereNag();
    }

    protected void premiereNag() {
        if (!TvApp.getApplication().isRegistered() && System.currentTimeMillis() - (86400000 * 7) > TvApp.getApplication().getLastNagTime()) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mActivity != null && !mActivity.isFinishing()) {
                        mActivity.showMessage(mApplication.getString(R.string.msg_premiere_nag_title), mApplication.getString(R.string.msg_premiere_nag_msg), 10000);
                        TvApp.getApplication().setLastNagTime(System.currentTimeMillis());
                    }

                }
            },2500);
        }
    }

    @Override
    protected void setupQueries(final IRowLoader rowLoader) {
        //Peek at the first library item to determine our order
        TvApp.getApplication().getApiClient().GetUserViews(TvApp.getApplication().getCurrentUser().getId(), new Response<ItemsResult>() {
            @Override
            public void onResponse(ItemsResult response) {
                //First library and in-progress
                mRows.add(new BrowseRowDef(mApplication.getString(R.string.lbl_library), new ViewQuery()));

                StdItemQuery resumeItems = new StdItemQuery();
                resumeItems.setIncludeItemTypes(new String[]{"Movie", "Episode", "Video", "Program"});
                resumeItems.setRecursive(true);
                resumeItems.setLimit(50);
                resumeItems.setFilters(new ItemFilter[]{ItemFilter.IsResumable});
                resumeItems.setSortBy(new String[]{ItemSortBy.DatePlayed});
                resumeItems.setSortOrder(SortOrder.Descending);
                mRows.add(new BrowseRowDef(mApplication.getString(R.string.lbl_continue_watching), resumeItems, 0, true, true, new ChangeTriggerType[]{ChangeTriggerType.MoviePlayback, ChangeTriggerType.TvPlayback}));

                //Now others based on first library type
                if (response.getTotalRecordCount() > 0) {
                    String firstType = ("tvshows".equals(response.getItems()[0].getCollectionType())) ? "s" : ("livetv".equals(response.getItems()[0].getCollectionType()) ? "t" : "m");
                    switch (firstType) {
                        case "s":
                            addNextUp();
                            addLatestMovies();
                            addOnNow();
                            break;

                        case "t":
                            addOnNow();
                            addNextUp();
                            addLatestMovies();
                            break;

                        default:
                            addLatestMovies();
                            addNextUp();
                            addOnNow();
                    }
                }
                //        StdItemQuery latestMusic = new StdItemQuery();
                //        latestMusic.setIncludeItemTypes(new String[]{"MusicAlbum"});
                //        latestMusic.setRecursive(true);
                //        latestMusic.setLimit(50);
                //        latestMusic.setSortBy(new String[]{ItemSortBy.DateCreated});
                //        latestMusic.setSortOrder(SortOrder.Descending);
                //        mRowDef.add(new BrowseRowDef("Latest Albums", latestMusic, 0));

                rowLoader.loadRows(mRows);
            }
        });



    }

    protected void addLatestMovies() {
        StdItemQuery latestMovies = new StdItemQuery();
        latestMovies.setIncludeItemTypes(new String[]{"Movie"});
        latestMovies.setRecursive(true);
        latestMovies.setLimit(50);
        latestMovies.setFilters(new ItemFilter[]{ItemFilter.IsUnplayed});
        latestMovies.setSortBy(new String[]{ItemSortBy.DateCreated});
        latestMovies.setSortOrder(SortOrder.Descending);
        mRows.add(new BrowseRowDef(mApplication.getString(R.string.lbl_latest_movies), latestMovies, 0, new ChangeTriggerType[]{ChangeTriggerType.LibraryUpdated, ChangeTriggerType.MoviePlayback}));


    }

    protected void addNextUp() {
        NextUpQuery nextUpQuery = new NextUpQuery();
        nextUpQuery.setUserId(TvApp.getApplication().getCurrentUser().getId());
        nextUpQuery.setLimit(50);
        nextUpQuery.setFields(new ItemFields[]{ItemFields.PrimaryImageAspectRatio, ItemFields.Overview});
        mRows.add(new BrowseRowDef(mApplication.getString(R.string.lbl_next_up_tv), nextUpQuery, new ChangeTriggerType[] {ChangeTriggerType.TvPlayback}));

    }

    protected void addOnNow() {
        if (TvApp.getApplication().getCurrentUser().getPolicy().getEnableLiveTvAccess()) {
            RecommendedProgramQuery onNow = new RecommendedProgramQuery();
            onNow.setIsAiring(true);
            onNow.setFields(new ItemFields[] {ItemFields.Overview, ItemFields.PrimaryImageAspectRatio});
            onNow.setUserId(TvApp.getApplication().getCurrentUser().getId());
            onNow.setLimit(20);
            mRows.add(new BrowseRowDef(mApplication.getString(R.string.lbl_on_now), onNow));
        }

    }

    @Override
    protected void addAdditionalRows(ArrayObjectAdapter rowAdapter) {
        super.addAdditionalRows(rowAdapter);

        HeaderItem gridHeader = new HeaderItem(rowAdapter.size(), mApplication.getString(R.string.lbl_settings), null);

        GridButtonPresenter mGridPresenter = new GridButtonPresenter();
        toolsRow = new ArrayObjectAdapter(mGridPresenter);
        toolsRow.add(new GridButton(SETTINGS, mApplication.getString(R.string.lbl_app_settings), R.drawable.gears));
        toolsRow.add(new GridButton(LOGOUT, mApplication.getString(R.string.lbl_logout) + TvApp.getApplication().getCurrentUser().getName(), R.drawable.logout));
        if (TvApp.getApplication().isConnectLogin()) toolsRow.add(new GridButton(LOGOUT_CONNECT, mApplication.getString(R.string.lbl_logout_connect), R.drawable.unlink));
        //give this some time to have validated
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!TvApp.getApplication().isRegistered() && !TvApp.getApplication().isPaid()) {
                    unlockButton = new GridButton(UNLOCK, mApplication.getString(R.string.lbl_unlock), R.drawable.unlock);
                    toolsRow.add(unlockButton);
                } else if (!TvApp.getApplication().isRegistered()) {
                    premiereButton = new GridButton(UNLOCK, mApplication.getString(R.string.btn_emby_premiere), R.drawable.embyicon);
                    toolsRow.add(premiereButton);
                }
            }
        }, 5000);

        sendLogsButton = new GridButton(REPORT, mApplication.getString(R.string.lbl_send_logs), R.drawable.upload);
        rowAdapter.add(new ListRow(gridHeader, toolsRow));
    }

    private void addLogsButton() {
        if (toolsRow != null && TvApp.getApplication().getPrefs().getBoolean("pref_enable_debug",false) && !Utils.isFireTv()) {
            if (toolsRow.indexOf(sendLogsButton) < 0) toolsRow.add(sendLogsButton);
            else if (toolsRow.indexOf(sendLogsButton) > -1) toolsRow.remove(sendLogsButton);
        }

    }

    @Override
    protected void setupEventListeners() {
        super.setupEventListeners();
        mClickedListener.registerListener(new ItemViewClickedListener());
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (item instanceof GridButton) {
                switch (((GridButton) item).getId()) {
                    case LOGOUT:
                        TvApp app = TvApp.getApplication();
                        if (app.getIsAutoLoginConfigured()) {
                            // Present user selection
                            app.setLoginApiClient(app.getApiClient());
                            Intent userIntent = new Intent(getActivity(), SelectUserActivity.class);
                            userIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                            getActivity().startActivity(userIntent);

                        }

                        getActivity().finish(); //don't actually log out because we handle it ourselves

                        break;
                    case SETTINGS:
                        Intent settings = new Intent(getActivity(), SettingsActivity.class);
                        getActivity().startActivity(settings);
                        break;
                    case UNLOCK:
                        Intent unlock = new Intent(getActivity(), UnlockActivity.class);
                        getActivity().startActivity(unlock);
                        break;
                    case REPORT:
                        Utils.reportError(getActivity(), "Send Log to Dev");
                        break;
                    case LOGOUT_CONNECT:
                        TvApp.getApplication().getConnectionManager().Logout(new EmptyResponse() {
                            @Override
                            public void onResponse() {
                                getActivity().finish();
                            }
                        });
                        break;
                    default:
                        Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT)
                                .show();
                        break;
                }
            }
        }
    }


}
