package mycelium.player_state.v1.model;

public interface Migration {
    int from();

    int to();

    StateBlob migrate(StateBlob blob);
}
