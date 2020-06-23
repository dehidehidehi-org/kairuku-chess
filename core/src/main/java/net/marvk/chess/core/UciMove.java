package net.marvk.chess.core;

import lombok.Data;
import net.marvk.chess.core.bitboards.Bitboard;

import java.util.Arrays;
import java.util.regex.Pattern;

@Data
public class UciMove {
    private static final Pattern SPLIT_PATTERN = Pattern.compile("(?<=\\G..)");
    private final Square source;
    private final Square target;
    private final Piece promote;

    public static UciMove parse(final String uciMove) {
        final String[] split = SPLIT_PATTERN.split(uciMove);

        final Square source = Square.getSquareFromFen(split[0]);
        final Square target = Square.getSquareFromFen(split[1]);

        final Piece promotion;

        if (split.length > 2) {
            promotion = ColoredPiece.getPieceFromSan(split[2].charAt(0)).getPiece();
        } else {
            promotion = null;
        }

        return new UciMove(source, target, promotion);
    }

    public static UciMove[] parseLine(final String line) {
        return Arrays.stream(line.split(" "))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .map(UciMove::parse)
                     .toArray(UciMove[]::new);
    }

    private static Bitboard getBoard(final UciMove[] uciMoves, final Bitboard startingBoard) {
        for (final UciMove uciMove : uciMoves) {
            final Bitboard.BBMove move =
                    startingBoard.generatePseudoLegalMoves()
                                 .stream()
                                 .filter(p -> p.asUciMove().equals(uciMove))
                                 .findFirst()
                                 .orElseThrow(() -> new IllegalStateException("Seemingly the opponent tried play an illegal move, this is probably a bug in the move generator. Move history was " + Arrays
                                         .toString(uciMoves)));

            startingBoard.make(move);
        }

        return startingBoard;
    }

    public static Bitboard getBoard(final UciMove[] uciMoves, final Fen fen) {
        return getBoard(uciMoves, new Bitboard(fen));
    }

    public static Bitboard getBoard(final UciMove[] uciMoves) {
        return getBoard(uciMoves, new Bitboard(Fen.STARTING_POSITION));
    }

    @Override
    public String toString() {
        final String squares = source.getFen() + target.getFen();

        if (promote == null) {
            return squares;
        } else {
            return squares + Character.toLowerCase(promote.ofColor(Color.BLACK).getSan());
        }
    }
}
