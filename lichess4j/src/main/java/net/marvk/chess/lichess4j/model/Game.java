package net.marvk.chess.lichess4j.model;

import lombok.Data;
import net.marvk.chess.core.Fen;

@Data
public class Game {
    private final String id;
    private final String fen;

    public Fen getFen() {
        return Fen.parse(fen);
    }
}
