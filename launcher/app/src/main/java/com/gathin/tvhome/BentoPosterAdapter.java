package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * One rule: short OK plays, long-press OK (~800 ms) opens the menu.
 * The 800 ms hold is timed in the adapter: a short press fires on ACTION_UP, a hold
 * fires the long-click once and sets clickBlocked so the release never falls through
 * into a launch. Touch long-press uses the same path.
 */
public class BentoPosterAdapter extends RecyclerView.Adapter<BentoPosterAdapter.ViewHolder> {
    private static final long LONG_PRESS_MS = 800L;

    public interface OnPosterKeyListener {
        boolean onPosterKey(int position, BentoTitle title, int keyCode, KeyEvent event, View view);
    }
    public interface OnPosterClickListener {
        void onPosterClick(int position, BentoTitle title, View view);
    }
    public interface OnPosterLongClickListener {
        void onPosterLongClick(int position, BentoTitle title, View view);
    }
    public interface OnPosterFocusListener {
        void onPosterFocus(int position, BentoTitle title, boolean hasFocus);
    }

    private final List<BentoTitle> items = new ArrayList<>();
    private OnPosterKeyListener keyListener;
    private OnPosterClickListener clickListener;
    private OnPosterLongClickListener longClickListener;
    private OnPosterFocusListener focusListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean showMyListBadge = true;

    /** My List row hides the "\u2713 My List" badge (redundant there); For you shows it. */
    public void setShowMyListBadge(boolean show) {
        showMyListBadge = show;
    }

    public void setItems(List<BentoTitle> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public List<BentoTitle> getItems() { return items; }
    public void setOnPosterKeyListener(OnPosterKeyListener l) { keyListener = l; }
    public void setOnPosterClickListener(OnPosterClickListener l) { clickListener = l; }
    public void setOnPosterLongClickListener(OnPosterLongClickListener l) { longClickListener = l; }
    public void setOnPosterFocusListener(OnPosterFocusListener l) { focusListener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bento_poster, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BentoTitle title = items.get(position);
        // Recycled views must not keep fly-animation transforms.
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.itemView.setAlpha(1f);
        holder.itemView.setTranslationX(0f);
        holder.itemView.setTranslationY(0f);
        if (title.isSkippedButton) {
            holder.image.setImageDrawable(null);
            holder.badge.setVisibility(View.GONE);
            holder.platform.setVisibility(View.GONE);
            holder.posterNew.setVisibility(View.GONE);
            holder.genre.setVisibility(View.GONE);
            holder.title.setText(title.title + "\nOK: Restore");
            holder.title.setMaxLines(2);
        } else {
            holder.title.setText(title.title);
            if (title.provider != null) {
                holder.platform.setText(title.provider);
                holder.platform.setVisibility(View.VISIBLE);
            } else {
                holder.platform.setVisibility(View.GONE);
            }
            if (title.isInMyList && showMyListBadge) {
                holder.badge.setText("\u2713 My List");
                holder.badge.setVisibility(View.VISIBLE);
            } else if (title.vpnLabel != null) {
                holder.badge.setText(title.vpnLabel);
                holder.badge.setVisibility(View.VISIBLE);
            } else {
                holder.badge.setVisibility(View.GONE);
            }
            holder.posterNew.setVisibility(title.isNewToday() ? View.VISIBLE : View.GONE);
            String genreText = title.genreText();
            if (genreText.isEmpty()) {
                holder.genre.setVisibility(View.GONE);
            } else {
                holder.genre.setText(genreText);
                holder.genre.setVisibility(View.VISIBLE);
            }
            BentoPosterLoader.get(holder.itemView.getContext()).loadPoster(title.posterUrl, holder.image);
        }

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start();
            else v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start();
            if (focusListener != null) focusListener.onPosterFocus(position, title, hasFocus);
        });

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null && !holder.clickBlocked) {
                clickListener.onPosterClick(holder.getBindingAdapterPosition(), title, v);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                holder.clickBlocked = true;
                longClickListener.onPosterLongClick(holder.getBindingAdapterPosition(), title, v);
                return true;
            }
            return false;
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.isLongPress() || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0
                            || event.getRepeatCount() > 0) {
                        fireLongPress(holder, title, v);
                        return true;
                    }
                    if (event.getRepeatCount() == 0) {
                        holder.clickBlocked = false;
                        if (holder.holdRunnable != null) handler.removeCallbacks(holder.holdRunnable);
                        holder.holdRunnable = () -> fireLongPress(holder, title, v);
                        handler.postDelayed(holder.holdRunnable, LONG_PRESS_MS);
                    }
                    return true;
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (holder.holdRunnable != null) handler.removeCallbacks(holder.holdRunnable);
                    if (holder.clickBlocked) {
                        holder.clickBlocked = false;
                        return true;
                    }
                    if (clickListener != null) {
                        clickListener.onPosterClick(holder.getBindingAdapterPosition(), title, v);
                    }
                    return true;
                }
            }
            if (keyListener != null) {
                return keyListener.onPosterKey(holder.getBindingAdapterPosition(), title, keyCode, event, v);
            }
            return false;
        });
    }

    private void fireLongPress(ViewHolder holder, BentoTitle title, View v) {
        if (holder.clickBlocked) return;
        holder.clickBlocked = true;
        if (holder.holdRunnable != null) handler.removeCallbacks(holder.holdRunnable);
        if (longClickListener != null) {
            longClickListener.onPosterLongClick(holder.getBindingAdapterPosition(), title, v);
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView badge;
        final TextView platform;
        final TextView posterNew;
        final TextView genre;
        boolean clickBlocked = false;
        Runnable holdRunnable;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.posterImage);
            title = itemView.findViewById(R.id.posterTitle);
            badge = itemView.findViewById(R.id.posterBadge);
            platform = itemView.findViewById(R.id.posterPlatform);
            posterNew = itemView.findViewById(R.id.posterNew);
            genre = itemView.findViewById(R.id.posterGenre);
        }
    }
}
