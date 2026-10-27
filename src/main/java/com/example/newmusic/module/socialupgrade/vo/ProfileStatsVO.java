package com.example.newmusic.module.socialupgrade.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ProfileStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long totalPlaySeconds;
    private int weekSongCount;
    private int listenDays;
    private List<ArtistItem> topArtists;
    private List<GenreItem> genres;
    /** 每项返回是否允许当前访客查看（来自逐项隐私） */
    private boolean playDurationVisible;
    private boolean weekCountVisible;
    private boolean topArtistVisible;
    private boolean genreVisible;
    private boolean listenDaysVisible;

    @Data
    public static class ArtistItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long artistId;
        private String artistName;
        private Long playCount;
    }

    @Data
    public static class GenreItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private String genre;
        private Long playCount;
    }
}
