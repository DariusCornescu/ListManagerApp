# app/models.py
from sqlalchemy import Column, Integer, String, Boolean, ForeignKey, DateTime, func
from sqlalchemy.orm import relationship
from .database import Base  

class User(Base):
    __tablename__ = "users"
    
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, nullable=False, index=True)
    email = Column(String(100), unique=True, nullable=False, index=True)
    hashed_password = Column(String(200), nullable=False)
    role = Column(String(20), default="USER")   # Possible roles: USER, ADMIN
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=func.now())

class Distributor(Base):
    __tablename__ = "distributors"
    
    id = Column(Integer, primary_key=True, index=True)
    distributor_name = Column(String(100), unique=True, nullable=False, index=True)
    created_at = Column(DateTime, default=func.now())
    
    products = relationship("Product", back_populates="distributor")

class Product(Base):
    __tablename__ = "products"
    
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(200), nullable=False, index=True)
    distributor_id = Column(Integer, ForeignKey("distributors.id"), nullable=False)
    aliases = Column(String, nullable=True)
    created_at = Column(DateTime, default=func.now())
    distributor = relationship("Distributor", back_populates="products")

class GlobalSession(Base):
    __tablename__ = "global_sessions"
    
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    is_active = Column(Boolean, default=True, index=True)
    created_at = Column(DateTime, default=func.now())
    completed_at = Column(DateTime, nullable=True)
    version = Column(Integer, default=0)
    
    items = relationship("GlobalSessionItem", back_populates="session")

class GlobalSessionItem(Base):
    __tablename__ = "global_session_items"
    
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, ForeignKey("global_sessions.id"), nullable=False)
    product_id = Column(Integer, ForeignKey("products.id"), nullable=False)
    quantity = Column(Integer, default = 1, nullable=False)
    created_at = Column(DateTime, default=func.now())
    updated_at = Column(DateTime, default=func.now(), onupdate=func.now())
    version = Column(Integer, default=0)
    
    session = relationship("GlobalSession", back_populates="items")
    product = relationship("Product")