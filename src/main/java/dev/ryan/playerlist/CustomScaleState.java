package dev.ryan.playerlist;

public interface CustomScaleState {
    void playerlist$setCustomScale(float scaleX, float scaleY, float scaleZ);

    float playerlist$getCustomScaleX();

    float playerlist$getCustomScaleY();

    float playerlist$getCustomScaleZ();

    void playerlist$clearCustomScale();
}
