package dev.antcraft;

/** A chunk generator that makes its dimension a strip; it knows the strip's footprint. */
public interface StripGenerator {
	StripBounds bounds();
}
