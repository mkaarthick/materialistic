/*
 * Copyright (c) 2016 Ha Duy Trung
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.hidroh.materialistic.widget;

import android.content.Intent;
import android.support.design.widget.Snackbar;
import android.support.v4.util.ArrayMap;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.hidroh.materialistic.AppUtils;
import io.github.hidroh.materialistic.ComposeActivity;
import io.github.hidroh.materialistic.MenuTintDelegate;
import io.github.hidroh.materialistic.R;
import io.github.hidroh.materialistic.accounts.UserServices;
import io.github.hidroh.materialistic.data.Favorite;
import io.github.hidroh.materialistic.data.FavoriteManager;
import io.github.hidroh.materialistic.data.ItemManager;

public class FavoriteRecyclerViewAdapter extends ListRecyclerViewAdapter
        <ListRecyclerViewAdapter.ItemViewHolder, Favorite> {

    public interface ActionModeDelegate {

        boolean startActionMode(ActionMode.Callback callback);
        boolean isInActionMode();
        void stopActionMode();
    }
    private final ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        private boolean mPendingClear;

        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            actionMode.getMenuInflater().inflate(R.menu.menu_favorite_action, menu);
            mMenuTintDelegate.onOptionsMenuCreated(menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(final ActionMode actionMode, MenuItem menuItem) {
            if (menuItem.getItemId() == R.id.menu_clear) {
                mAlertDialogBuilder
                        .init(mContext)
                        .setMessage(R.string.confirm_clear_selected)
                        .setPositiveButton(android.R.string.ok,
                                (dialog, which) -> {
                                    mPendingClear = true;
                                    removeSelection();
                                    actionMode.finish();
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                        .show();
                return true;
            }

            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {
            if (!isAttached()) {
                return;
            }
            mActionModeDelegate.stopActionMode();
            if (mPendingClear) {
                mPendingClear = false;
            } else {
                mSelected.clear();
            }
            notifyDataSetChanged();
        }
    };
    private ActionModeDelegate mActionModeDelegate;
    private MenuTintDelegate mMenuTintDelegate;
    private FavoriteManager.Cursor mCursor;
    private ArrayMap<Integer, String> mSelected = new ArrayMap<>();
    private int mPendingAdd = -1;

    public FavoriteRecyclerViewAdapter(ActionModeDelegate actionModeDelegate) {
        super();
        mActionModeDelegate = actionModeDelegate;
    }

    @Override
    public void onAttachedToRecyclerView(final RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mMenuTintDelegate = new MenuTintDelegate();
        mMenuTintDelegate.onActivityCreated(mContext);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(RecyclerView recyclerView,
                                  RecyclerView.ViewHolder viewHolder,
                                  RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public int getSwipeDirs(RecyclerView recyclerView,
                                    RecyclerView.ViewHolder viewHolder) {
                if (mActionModeDelegate.isInActionMode()) {
                    return 0;
                }
                return super.getSwipeDirs(recyclerView, viewHolder);
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                dismiss(viewHolder.getAdapterPosition());
            }
        }).attachToRecyclerView(recyclerView);
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mActionModeDelegate = null;
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
    }

    @Override
    public ItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ItemViewHolder(mInflater.inflate(R.layout.item_favorite, parent, false));
    }

    @Override
    public int getItemCount() {
        return mCursor == null ? 0 : mCursor.getCount();
    }

    @Override
    protected void bindItem(final ItemViewHolder holder) {
        final Favorite favorite = getItem(holder.getAdapterPosition());
        holder.itemView.setOnLongClickListener(v -> {
            if (mActionModeDelegate.startActionMode(mActionModeCallback)) {
                toggle(favorite.getId(), holder.getAdapterPosition());
                return true;
            }

            return false;
        });
        holder.mStoryView.getMoreOptions().setOnClickListener(v -> showMoreOptions(v, favorite));
    }

    @Override
    protected boolean isItemAvailable(Favorite item) {
        return item != null;
    }

    @Override
    protected void handleItemClick(Favorite item, ItemViewHolder holder) {
        if (!mActionModeDelegate.isInActionMode()) {
            super.handleItemClick(item, holder);
        } else {
            toggle(item.getId(), holder.getLayoutPosition());
        }
    }

    @Override
    protected Favorite getItem(int position) {
        if (mCursor == null || !mCursor.moveToPosition(position)) {
            return null;
        }
        return mCursor.getFavorite();
    }

    @Override
    protected boolean isSelected(String itemId) {
        return super.isSelected(itemId) || mSelected.containsValue(itemId);
    }

    @Override
    protected int getItemCacheMode() {
        return ItemManager.MODE_CACHE;
    }

    public void setCursor(FavoriteManager.Cursor cursor) {
        mCursor = cursor;
        if (cursor == null) {
            notifyDataSetChanged();
            return;
        }
        if (!mSelected.isEmpty()) { // has pending removals, notify removed
            List<Integer> positions = new ArrayList<>(mSelected.keySet());
            Collections.sort(positions);
            mSelected.clear();
            for (int i = positions.size() - 1; i >= 0; i--) {
                notifyItemRemoved(positions.get(i));
            }
        } else if (mPendingAdd >= 0) { // has pending insertion, notify inserted
            notifyItemInserted(mPendingAdd);
            mPendingAdd = -1;
        } else { // no pending changes, simply refresh list
            notifyDataSetChanged();
        }
    }

    private void removeSelection() {
        mFavoriteManager.remove(mContext, mSelected.values());
    }

    private void dismiss(final int position) {
        final Favorite item = getItem(position);
        mSelected.put(position, item.getId());
        mFavoriteManager.remove(mContext, mSelected.values());
        Snackbar.make(mRecyclerView, R.string.toast_removed, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> {
                    mPendingAdd = position;
                    mFavoriteManager.add(mContext, item);
                })
                .show();
    }

    private void toggle(String itemId, int position) {
        if (mSelected.containsValue(itemId)) {
            mSelected.remove(position);
        } else {
            mSelected.put(position, itemId);
        }
        notifyItemChanged(position);
    }

    private void showMoreOptions(View v, final Favorite item) {
        mPopupMenu.create(mContext, v, Gravity.NO_GRAVITY)
                .inflate(R.menu.menu_contextual_favorite)
                .setOnMenuItemClickListener(menuItem -> {
                    if (menuItem.getItemId() == R.id.menu_contextual_vote) {
                        vote(item);
                        return true;
                    }
                    if (menuItem.getItemId() == R.id.menu_contextual_comment) {
                        mContext.startActivity(new Intent(mContext, ComposeActivity.class)
                                .putExtra(ComposeActivity.EXTRA_PARENT_ID, item.getId())
                                .putExtra(ComposeActivity.EXTRA_PARENT_TEXT, item.getDisplayedTitle()));
                        return true;
                    }
                    return false;
                })
                .show();
    }

    private void vote(final Favorite item) {
        mUserServices.voteUp(mContext, item.getId(), new VoteCallback(this));
    }

    private void onVoted(Boolean successful) {
        if (successful == null) {
            Toast.makeText(mContext, R.string.vote_failed, Toast.LENGTH_SHORT).show();
        } else if (successful) {
            Toast.makeText(mContext, R.string.voted, Toast.LENGTH_SHORT).show();
        } else {
            AppUtils.showLogin(mContext, mAlertDialogBuilder);
        }
    }

    private static class VoteCallback extends UserServices.Callback {
        private final WeakReference<FavoriteRecyclerViewAdapter> mAdapter;

        public VoteCallback(FavoriteRecyclerViewAdapter adapter) {
            mAdapter = new WeakReference<>(adapter);
        }

        @Override
        public void onDone(boolean successful) {
            if (mAdapter.get() != null && mAdapter.get().isAttached()) {
                mAdapter.get().onVoted(successful);
            }
        }

        @Override
        public void onError() {
            if (mAdapter.get() != null && mAdapter.get().isAttached()) {
                mAdapter.get().onVoted(null);
            }
        }
    }
}
